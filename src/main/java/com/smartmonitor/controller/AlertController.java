package com.smartmonitor.controller;

import com.smartmonitor.entity.AlertInfo;
import com.smartmonitor.service.AlertService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 入侵告警 REST API
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /** 获取最近告警列表 */
    @GetMapping("/list")
    public List<AlertInfo> getAlerts(@RequestParam(required = false) Integer status) {
        if (status != null) {
            return alertService.getByStatus(status);
        }
        return alertService.getRecentAlerts();
    }

    /** 告警统计 */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return alertService.getStats();
    }

    /** 处理告警 */
    @PutMapping("/{id}/handle")
    public Map<String, Object> handleAlert(@PathVariable Long id,
                                            @RequestParam Integer status,
                                            @RequestParam(required = false) String note) {
        AlertInfo alert = alertService.handleAlert(id, status, note);
        Map<String, Object> result = new HashMap<>();
        if (alert != null) {
            result.put("success", true);
            result.put("message", "告警已处理");
            result.put("alert", alert);
        } else {
            result.put("success", false);
            result.put("message", "告警不存在");
        }
        return result;
    }

    /** 删除单条告警（仅管理员，JwtInterceptor 已拦截非 admin 角色） */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteAlert(@PathVariable Long id) {
        boolean ok = alertService.deleteAlert(id);
        Map<String, Object> result = new HashMap<>();
        result.put("success", ok);
        result.put("message", ok ? "告警已删除" : "告警不存在");
        return ok ? ResponseEntity.ok(result) : ResponseEntity.status(404).body(result);
    }

    /** 调用 DeepSeek 大模型对单条告警进行 AI 研判（结果入库缓存） */
    @PostMapping("/{id}/analyze")
    public Map<String, Object> analyzeWithAi(@PathVariable Long id) {
        return alertService.analyzeWithAi(id);
    }
}
