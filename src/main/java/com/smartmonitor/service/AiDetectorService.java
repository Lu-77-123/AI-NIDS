package com.smartmonitor.service;

import com.smartmonitor.entity.AiConfig;
import com.smartmonitor.entity.TrafficData;
import com.smartmonitor.repository.TrafficDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 异常检测服务（Java 内置稳健统计检测）
 * 基线算法：中位数 + MAD（1.4826×MAD ≈ 正态标准差）滑动窗口，
 * 不会被攻击尖峰拉高，避免攻击过后正常流量被误判为异常。
 * 判定条件：入向流量"向上突增"超过 2 倍稳健标准差，且绝对流量达到攻击量级下限。
 * 告警语义研判由 DeepSeekService 负责，本类不依赖任何外部 AI 微服务。
 */
@Service
public class AiDetectorService {

    private static final Logger logger = LoggerFactory.getLogger(AiDetectorService.class);

    /** 稳健尺度换算系数：1.4826×MAD 近似正态分布标准差（固定数学常数，不可配） */
    public static final double SCALE_FACTOR = 1.4826;
    /** 以下四项为出厂默认值，运行期实际生效值见 AiConfigService（可在前端 AI 配置中心修改） */
    public static final double DEFAULT_Z_THRESHOLD = 2.0;
    public static final long DEFAULT_MIN_ANOMALY_BPS = 50000;
    public static final int DEFAULT_WINDOW_SIZE = 30;
    public static final int DEFAULT_MIN_SAMPLES = 5;

    private final TrafficDataRepository trafficDataRepository;
    private final AiConfigService aiConfigService;
    private final AutoEncoderService autoEncoderService;

    public AiDetectorService(TrafficDataRepository trafficDataRepository,
                             AiConfigService aiConfigService,
                             AutoEncoderService autoEncoderService) {
        this.trafficDataRepository = trafficDataRepository;
        this.aiConfigService = aiConfigService;
        this.autoEncoderService = autoEncoderService;
    }

    /**
     * 批量检测异常
     * @param trafficList 流量数据列表
     * @return deviceId -> [anomalyScore, isAnomaly]
     */
    public Map<Long, double[]> detectBatch(List<TrafficData> trafficList) {
        if (trafficList == null || trafficList.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, double[]> result = statisticalDetect(trafficList);
        applyAutoEncoderSignal(trafficList, result);
        return result;
    }

    /**
     * 自编码器第二信号。
     *
     * 不改变是否告警（主判定仍由稳健统计给出，避免模型误报放大），
     * 只在两个信号一致时加强异常评分，使研判与告警排序更可信。
     * 统计未判异常而 AE 判异常时，仅记录日志，用于观察模型的独立检出情况。
     */
    private void applyAutoEncoderSignal(List<TrafficData> trafficList, Map<Long, double[]> result) {
        if (!autoEncoderService.isReady()) return;
        for (TrafficData td : trafficList) {
            double[] r = result.get(td.getDeviceId());
            if (r == null) continue;
            try {
                double err = autoEncoderService.errorOf(td.getDeviceId());
                if (err < 0 || !autoEncoderService.isAnomalous(err)) continue;
                if (r[1] == 1) {
                    r[0] = Math.max(-1.0, r[0] - 0.1);
                } else {
                    logger.debug("自编码器检出重构异常但统计未判: deviceId={}, err={}, threshold={}",
                            td.getDeviceId(), String.format("%.6f", err), autoEncoderService.getThreshold());
                }
            } catch (Exception e) {
                logger.debug("自编码器评分失败: deviceId={}, err={}", td.getDeviceId(), e.getMessage());
            }
        }
    }

    /**
     * 稳健统计检测（中位数 + MAD 滑动窗口）
     */
    private Map<Long, double[]> statisticalDetect(List<TrafficData> trafficList) {
        Map<Long, double[]> result = new HashMap<>();
        final AiConfig cfg = aiConfigService.get();

        for (TrafficData td : trafficList) {
            try {
                // 滑动窗口取最近 windowSize 条（降序取回后反转为升序），随采集推进自动滚动
                List<TrafficData> desc = trafficDataRepository
                        .findTop200ByDeviceIdOrderByCollectTimeDesc(td.getDeviceId());
                int win = Math.min(cfg.getWindowSize(), desc.size());
                List<TrafficData> history = new java.util.ArrayList<>(desc.subList(0, win));
                java.util.Collections.reverse(history);

                double score = 0.0;
                int isAnomaly = 0;

                if (history.size() >= cfg.getMinSamples()) {
                    double[] inBpsArr = history.stream()
                            .mapToDouble(t -> t.getInBps() != null ? t.getInBps() : 0L)
                            .toArray();
                    double median = medianOf(inBpsArr);
                    double[] dev = new double[inBpsArr.length];
                    for (int i = 0; i < inBpsArr.length; i++) {
                        dev[i] = Math.abs(inBpsArr[i] - median);
                    }
                    double mad = medianOf(dev);
                    double scale = SCALE_FACTOR * mad;
                    if (scale <= 0) {
                        // 窗口内数值几乎全相同：用中位数的 30% 作为最小灵敏度
                        scale = Math.max(median * 0.3, 100);
                    }

                    double currentIn = td.getInBps() != null ? td.getInBps() : 0L;
                    double zScore = (currentIn - median) / scale;
                    // 仅"向上突增"超过灵敏度阈值，且绝对流量达到攻击量级下限，才判异常
                    // （实验室正常流量约 1~3 Kbps，限速洪泛实测约 200 Kbps，50 Kbps 下限可干净区分）
                    if (zScore > cfg.getZThreshold() && currentIn >= cfg.getMinAnomalyBps()) {
                        isAnomaly = 1;
                        score = -0.4;
                    } else {
                        score = 0.2 - Math.abs(zScore) * 0.1;
                    }
                }

                result.put(td.getDeviceId(), new double[]{score, isAnomaly});
            } catch (Exception e) {
                result.put(td.getDeviceId(), new double[]{0.0, 0});
            }
        }

        logger.info("统计检测完成: {} 台设备", result.size());
        return result;
    }

    /** 中位数（会复制并排序入参） */
    private static double medianOf(double[] arr) {
        if (arr == null || arr.length == 0) return 0;
        double[] sorted = arr.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    public long getMinAnomalyBps() {
        return aiConfigService.get().getMinAnomalyBps();
    }
}
