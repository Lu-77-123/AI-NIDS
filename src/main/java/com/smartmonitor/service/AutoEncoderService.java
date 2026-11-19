package com.smartmonitor.service;

import com.smartmonitor.entity.TrafficData;
import com.smartmonitor.ml.AutoEncoder;
import com.smartmonitor.repository.TrafficDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自编码器异常检测服务。
 *
 * 职责：
 *   1. 启动后异步用历史流量训练一个轻量自编码器（无监督，只用"正常"样本）；
 *   2. 推理时计算当前设备最近窗口的重构误差，作为异常检测的第二路信号；
 *   3. 定时重训，使基线跟随网络流量的长期变化。
 *
 * 定位：自编码器不单独决定是否告警（避免误报放大），
 * 而是作为"佐证信号"参与评分增强与告警研判取证。
 */
@Service
public class AutoEncoderService {

    private static final Logger logger = LoggerFactory.getLogger(AutoEncoderService.class);

    /** 输入窗口：最近 8 轮采集的入/出向速率（8×2=16 维） */
    public static final int WINDOW = 8;
    public static final int INPUT_DIM = WINDOW * 2;
    /** 网络结构：16 → 32 → 12 → 4（瓶颈） → 12 → 32 → 16 */
    private static final int[] NETWORK = {INPUT_DIM, 32, 12, 4, 12, 32, INPUT_DIM};
    /** 训练样本上限与最低要求 */
    private static final int MAX_SAMPLES = 1500;
    private static final int MIN_SAMPLES = 100;
    /** 归一化参考值取分位数，避免单个尖峰把正常流量压成 0 */
    private static final double NORMALIZE_PERCENTILE = 0.995;

    private final TrafficDataRepository trafficDataRepository;

    private volatile AutoEncoder model;
    private volatile double threshold;
    private volatile double normRef = 1.0;
    private volatile double trainRmse = -1;
    private volatile int sampleCount;
    private volatile boolean training;
    private volatile long lastTrainTime;

    public AutoEncoderService(TrafficDataRepository trafficDataRepository) {
        this.trafficDataRepository = trafficDataRepository;
    }

    @PostConstruct
    public void init() {
        Thread t = new Thread(this::trainModel, "ae-trainer");
        t.setDaemon(true);
        t.start();
    }

    /** 每 30 分钟重训一次，让模型跟随流量基线漂移 */
    @Scheduled(fixedDelay = 30 * 60 * 1000L)
    public void scheduledRetrain() {
        if (model != null) {
            trainModel();
        }
    }

    /**
     * 用历史数据训练模型。任何异常都不影响主检测链路，只记录日志。
     */
    public synchronized void trainModel() {
        if (training) return;
        training = true;
        long start = System.currentTimeMillis();
        try {
            List<TrafficData> all = trafficDataRepository.findAll(
                    Sort.by(Sort.Direction.DESC, "collectTime"));
            if (all == null || all.isEmpty()) {
                logger.info("自编码器训练跳过：暂无流量数据");
                return;
            }

            List<double[]> samples = buildSamples(all);
            if (samples.size() < MIN_SAMPLES) {
                logger.info("自编码器训练跳过：有效样本 {} 条，低于下限 {}", samples.size(), MIN_SAMPLES);
                return;
            }

            // 归一化参考值：所有原始值的 99.5 分位
            normRef = percentileOfRawMax(all, NORMALIZE_PERCENTILE);
            double[][] normalized = new double[samples.size()][];
            for (int i = 0; i < samples.size(); i++) {
                normalized[i] = normalize(samples.get(i));
            }

            AutoEncoder ae = new AutoEncoder(NETWORK);
            double rmse = ae.train(normalized, 150, 0.05, 32);

            // 阈值取训练集重构误差的 99 分位并留 30% 余量
            double[] errors = new double[normalized.length];
            for (int i = 0; i < normalized.length; i++) {
                errors[i] = ae.reconstructionError(normalized[i]);
            }
            double p99 = percentile(errors, 0.99);
            double thr = Math.max(p99 * 1.3, 1e-4);

            this.model = ae;
            this.threshold = thr;
            this.trainRmse = rmse;
            this.sampleCount = normalized.length;
            this.lastTrainTime = System.currentTimeMillis();
            logger.info("自编码器训练完成: 样本={}, 末轮MSE={}, 阈值={}, 耗时={}ms",
                    sampleCount, String.format("%.6f", rmse), String.format("%.6f", thr),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            logger.warn("自编码器训练失败: {}", e.getMessage());
        } finally {
            training = false;
        }
    }

    /**
     * 计算指定设备当前窗口的重构误差。
     *
     * @return 归一化空间中的均方误差；模型未就绪或样本不足时返回 -1
     */
    public double errorOf(Long deviceId) {
        AutoEncoder ae = this.model;
        if (ae == null || deviceId == null) return -1;
        try {
            List<TrafficData> desc = trafficDataRepository
                    .findTop200ByDeviceIdOrderByCollectTimeDesc(deviceId);
            if (desc == null || desc.size() < WINDOW) return -1;
            List<TrafficData> window = new ArrayList<>(desc.subList(0, WINDOW));
            window.sort(Comparator.comparing(TrafficData::getCollectTime,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            double[] raw = flatten(window);
            return ae.reconstructionError(normalize(raw));
        } catch (Exception e) {
            return -1;
        }
    }

    public boolean isReady() {
        return model != null && threshold > 0;
    }

    public boolean isAnomalous(double error) {
        return isReady() && error >= 0 && error > threshold;
    }

    public double getThreshold() {
        return threshold;
    }

    /** 供管理接口与研判取证使用的运行状态 */
    public Map<String, Object> status(Long deviceId) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("ready", isReady());
        s.put("training", training);
        s.put("samples", sampleCount);
        s.put("threshold", round6(threshold));
        s.put("trainRmse", round6(trainRmse));
        s.put("normRef", round2(normRef));
        s.put("window", WINDOW);
        s.put("structure", "16-32-12-4-12-32-16");
        if (lastTrainTime > 0) {
            s.put("lastTrainTime", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date(lastTrainTime)));
        }
        if (deviceId != null) {
            double err = errorOf(deviceId);
            s.put("deviceError", round6(err));
            s.put("deviceAnomalous", isAnomalous(err));
        }
        return s;
    }

    // ==================== 内部实现 ====================

    /** 按设备分组、按时间升序滑动窗口构造样本（原始单位，未归一化） */
    private List<double[]> buildSamples(List<TrafficData> descList) {
        Map<Long, List<TrafficData>> byDevice = new HashMap<>();
        for (TrafficData t : descList) {
            if (t.getDeviceId() == null) continue;
            byDevice.computeIfAbsent(t.getDeviceId(), k -> new ArrayList<>()).add(t);
        }

        List<double[]> samples = new ArrayList<>();
        for (List<TrafficData> list : byDevice.values()) {
            // 传入是时间倒序，这里反转为升序
            list.sort(Comparator.comparing(TrafficData::getCollectTime,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            for (int i = WINDOW - 1; i < list.size(); i++) {
                List<TrafficData> win = list.subList(i - WINDOW + 1, i + 1);
                samples.add(flatten(win));
            }
        }
        // 只保留最近的 MAX_SAMPLES 条样本，避免旧模式主导训练
        if (samples.size() > MAX_SAMPLES) {
            samples = new ArrayList<>(samples.subList(samples.size() - MAX_SAMPLES, samples.size()));
        }
        return samples;
    }

    private static double[] flatten(List<TrafficData> window) {
        double[] v = new double[INPUT_DIM];
        for (int i = 0; i < window.size() && i < WINDOW; i++) {
            TrafficData t = window.get(i);
            v[i * 2] = t.getInBps() != null ? t.getInBps() : 0;
            v[i * 2 + 1] = t.getOutBps() != null ? t.getOutBps() : 0;
        }
        return v;
    }

    /** log1p 压缩长尾后按参考值归一化到 [0,1] */
    private double[] normalize(double[] raw) {
        double[] out = new double[raw.length];
        double denom = Math.log1p(Math.max(normRef, 1.0));
        for (int i = 0; i < raw.length; i++) {
            double v = Math.log1p(Math.max(raw[i], 0)) / denom;
            out[i] = Math.min(1.0, Math.max(0.0, v));
        }
        return out;
    }

    /** 取所有流量值中的高分位数作为归一化参考 */
    private static double percentileOfRawMax(List<TrafficData> list, double p) {
        List<Double> values = new ArrayList<>(list.size() * 2);
        for (TrafficData t : list) {
            if (t.getInBps() != null) values.add((double) t.getInBps());
            if (t.getOutBps() != null) values.add((double) t.getOutBps());
        }
        if (values.isEmpty()) return 1.0;
        Collections.sort(values);
        int idx = (int) Math.min(values.size() - 1, Math.floor(values.size() * p));
        return Math.max(values.get(idx), 1.0);
    }

    private static double percentile(double[] arr, double p) {
        if (arr.length == 0) return 0;
        double[] sorted = arr.clone();
        java.util.Arrays.sort(sorted);
        int idx = (int) Math.min(sorted.length - 1, Math.floor(sorted.length * p));
        return sorted[idx];
    }

    private static double round6(double v) {
        return v < 0 ? v : Math.round(v * 1e6) / 1e6;
    }

    private static double round2(double v) {
        return v < 0 ? v : Math.round(v * 100) / 100.0;
    }
}
