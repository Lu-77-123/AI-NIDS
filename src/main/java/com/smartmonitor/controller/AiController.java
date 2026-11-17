package com.smartmonitor.controller;

import com.smartmonitor.entity.AiConfig;
import com.smartmonitor.service.AiConfigService;
import com.smartmonitor.service.AiDetectorService;
import com.smartmonitor.service.AlertService;
import com.smartmonitor.service.DeepSeekService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 智能检测引擎控制器
 * GET  /api/ai/engine —— 展示当前真实生效的检测参数与研判模型状态（含出厂默认值，供前端"恢复默认"对照）
 * PUT  /api/ai/config —— 修改运行期配置（仅管理员，JwtInterceptor 已拦截非 admin 角色），保存后立即生效
 * POST /api/ai/config/reset —— 恢复出厂默认值（仅管理员）
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiDetectorService aiDetectorService;
    private final AlertService alertService;
    private final DeepSeekService deepSeekService;
    private final AiConfigService aiConfigService;

    public AiController(AiDetectorService aiDetectorService,
                        AlertService alertService,
                        DeepSeekService deepSeekService,
                        AiConfigService aiConfigService) {
        this.aiDetectorService = aiDetectorService;
        this.alertService = alertService;
        this.deepSeekService = deepSeekService;
        this.aiConfigService = aiConfigService;
    }

    /** 检测引擎状态与参数（当前生效值 + 出厂默认值） */
    @GetMapping("/engine")
    public ResponseEntity<Map<String, Object>> engine() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("detector", "Java 内置稳健统计检测");
        result.put("baselineAlgorithm", "中位数 + MAD");
        result.put("scaleFactor", AiDetectorService.SCALE_FACTOR);
        result.putAll(aiConfigService.toMap());
        // 出厂默认值（前端"恢复默认"按钮对照展示）
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("zThreshold", AiDetectorService.DEFAULT_Z_THRESHOLD);
        defaults.put("minAnomalyBps", AiDetectorService.DEFAULT_MIN_ANOMALY_BPS);
        defaults.put("windowSize", AiDetectorService.DEFAULT_WINDOW_SIZE);
        defaults.put("minSamples", AiDetectorService.DEFAULT_MIN_SAMPLES);
        defaults.put("cooldownMinutes", 5);
        result.put("defaults", defaults);
        result.put("deepseekEnabled", deepSeekService.isEnabled());
        result.put("deepseekModel", deepSeekService.getModel());
        result.put("cooldownMinutes", alertService.getCooldownMinutes());
        return ResponseEntity.ok(result);
    }

    /** 修改运行期配置（仅管理员） */
    @PutMapping("/config")
    public ResponseEntity<?> updateConfig(@RequestBody Map<String, Object> body,
                                          HttpServletRequest request) {
        try {
            Double z = body.get("zThreshold") != null ? ((Number) body.get("zThreshold")).doubleValue() : null;
            Long minBps = body.get("minAnomalyBps") != null ? ((Number) body.get("minAnomalyBps")).longValue() : null;
            Integer window = body.get("windowSize") != null ? ((Number) body.get("windowSize")).intValue() : null;
            Integer minSamples = body.get("minSamples") != null ? ((Number) body.get("minSamples")).intValue() : null;
            Long cooldown = body.get("cooldownMinutes") != null ? ((Number) body.get("cooldownMinutes")).longValue() : null;
            String model = body.get("deepseekModel") != null ? String.valueOf(body.get("deepseekModel")) : null;
            String updatedBy = (String) request.getAttribute("currentUsername");

            AiConfig c = aiConfigService.update(z, minBps, window, minSamples, cooldown, model, updatedBy);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("message", "配置已保存并立即生效");
            resp.putAll(aiConfigService.toMap());
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("message", "配置保存失败：" + e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    /** 恢复出厂默认值（仅管理员） */
    @PostMapping("/config/reset")
    public ResponseEntity<?> resetConfig(HttpServletRequest request) {
        String updatedBy = (String) request.getAttribute("currentUsername");
        aiConfigService.resetDefaults(updatedBy);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("message", "已恢复出厂默认配置并立即生效");
        resp.putAll(aiConfigService.toMap());
        return ResponseEntity.ok(resp);
    }
}
