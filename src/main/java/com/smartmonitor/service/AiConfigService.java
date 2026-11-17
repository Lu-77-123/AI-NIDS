package com.smartmonitor.service;

import com.smartmonitor.entity.AiConfig;
import com.smartmonitor.repository.AiConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 引擎运行期配置服务：单行配置读写 + 范围校验。
 * 各检测/告警/研判组件每次执行时从这里取当前生效值，保存后立即生效。
 */
@Service
public class AiConfigService {

    private static final Logger logger = LoggerFactory.getLogger(AiConfigService.class);

    private final AiConfigRepository repository;
    private volatile AiConfig cache;

    public AiConfigService(AiConfigRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        get(); // 首次启动自动落默认值
    }

    /** 当前生效配置（内存缓存，读多写少） */
    public AiConfig get() {
        AiConfig c = cache;
        if (c != null) return c;
        synchronized (this) {
            if (cache == null) {
                cache = repository.findFirstByOrderByIdAsc();
                if (cache == null) {
                    cache = new AiConfig();
                    cache.setUpdateTime(LocalDateTime.now());
                    cache.setUpdatedBy("system");
                    cache = repository.save(cache);
                    logger.info("AI 引擎配置初始化默认值: z={}, minBps={}, window={}, minSamples={}, cooldown={}min",
                            cache.getZThreshold(), cache.getMinAnomalyBps(),
                            cache.getWindowSize(), cache.getMinSamples(), cache.getCooldownMinutes());
                }
            }
            return cache;
        }
    }

    /**
     * 更新配置（范围校验，非法值抛 IllegalArgumentException）
     * 传 null 的字段保持原值；deepseekModel 传空串表示恢复使用 yml 配置。
     */
    public synchronized AiConfig update(Double zThreshold, Long minAnomalyBps,
                                        Integer windowSize, Integer minSamples,
                                        Long cooldownMinutes, String deepseekModel,
                                        String updatedBy) {
        AiConfig c = get();
        // 先在局部变量上完成全部校验，全部通过后再写回实体——避免直接在缓存实体上
        // 逐字段赋值导致：后续字段校验失败抛异常时，前面字段已污染内存缓存。
        double newZ = zThreshold != null ? zThreshold : c.getZThreshold();
        long newMinBps = minAnomalyBps != null ? minAnomalyBps : c.getMinAnomalyBps();
        int newWindow = windowSize != null ? windowSize : c.getWindowSize();
        int newMinSamples = minSamples != null ? minSamples : c.getMinSamples();
        long newCooldown = cooldownMinutes != null ? cooldownMinutes : c.getCooldownMinutes();
        String newModel = c.getDeepseekModel();
        if (deepseekModel != null) {
            String m = deepseekModel.trim();
            if (!m.isEmpty() && !m.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("模型名只允许字母/数字/._-，最长 64 字符");
            newModel = m.isEmpty() ? null : m;
        }

        if (newZ < 0.5 || newZ > 10) throw new IllegalArgumentException("z 阈值须在 0.5 ~ 10 之间");
        if (newMinBps < 1000 || newMinBps > 100_000_000) throw new IllegalArgumentException("绝对流量下限须在 1 Kbps ~ 100 Mbps 之间");
        if (newWindow < 10 || newWindow > 200) throw new IllegalArgumentException("基线窗口须在 10 ~ 200 条之间");
        if (newMinSamples < 3 || newMinSamples > 50) throw new IllegalArgumentException("最少样本数须在 3 ~ 50 之间");
        if (newMinSamples > newWindow) throw new IllegalArgumentException("最少样本数不能大于基线窗口");
        if (newCooldown < 0 || newCooldown > 1440) throw new IllegalArgumentException("冷却时间须在 0 ~ 1440 分钟之间");

        c.setZThreshold(newZ);
        c.setMinAnomalyBps(newMinBps);
        c.setWindowSize(newWindow);
        c.setMinSamples(newMinSamples);
        c.setCooldownMinutes(newCooldown);
        c.setDeepseekModel(newModel);
        c.setUpdatedBy(updatedBy != null ? updatedBy : "admin");
        c.setUpdateTime(LocalDateTime.now());
        cache = repository.save(c);
        logger.info("AI 引擎配置已更新: z={}, minBps={}, window={}, minSamples={}, cooldown={}min, model={}, by={}",
                cache.getZThreshold(), cache.getMinAnomalyBps(), cache.getWindowSize(),
                cache.getMinSamples(), cache.getCooldownMinutes(),
                cache.getDeepseekModel() == null ? "(yml默认)" : cache.getDeepseekModel(), updatedBy);
        return cache;
    }

    /** 恢复出厂默认值 */
    public synchronized AiConfig resetDefaults(String updatedBy) {
        AiConfig c = get();
        c.setZThreshold(2.0);
        c.setMinAnomalyBps(50000);
        c.setWindowSize(30);
        c.setMinSamples(5);
        c.setCooldownMinutes(5);
        c.setDeepseekModel(null);
        c.setUpdatedBy(updatedBy != null ? updatedBy : "admin");
        c.setUpdateTime(LocalDateTime.now());
        cache = repository.save(c);
        logger.info("AI 引擎配置已恢复默认, by={}", updatedBy);
        return cache;
    }

    /** 组装成前端展示 Map（含默认值，供"恢复默认"对照） */
    public Map<String, Object> toMap() {
        AiConfig c = get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zThreshold", c.getZThreshold());
        m.put("minAnomalyBps", c.getMinAnomalyBps());
        m.put("windowSize", c.getWindowSize());
        m.put("minSamples", c.getMinSamples());
        m.put("cooldownMinutes", c.getCooldownMinutes());
        m.put("deepseekModelOverride", c.getDeepseekModel() == null ? "" : c.getDeepseekModel());
        m.put("updatedBy", c.getUpdatedBy());
        m.put("updateTime", c.getUpdateTime() != null ? c.getUpdateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
        return m;
    }
}
