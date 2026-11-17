package com.smartmonitor.controller;

import com.smartmonitor.entity.AgentAction;
import com.smartmonitor.entity.AgentPolicy;
import com.smartmonitor.service.AgentChatService;
import com.smartmonitor.service.AgentDecisionEngine;
import com.smartmonitor.service.AgentPolicyService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体决策 API
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentDecisionEngine decisionEngine;
    private final AgentChatService chatService;
    private final AgentPolicyService policyService;

    public AgentController(AgentDecisionEngine decisionEngine,
                           AgentChatService chatService,
                           AgentPolicyService policyService) {
        this.decisionEngine = decisionEngine;
        this.chatService = chatService;
        this.policyService = policyService;
    }

    /** 查询决策列表（可选 status 过滤） */
    @GetMapping("/actions")
    public List<AgentAction> getActions(@RequestParam(required = false) Integer status) {
        if (status != null) {
            return decisionEngine.getByStatus(status);
        }
        return decisionEngine.getAllActions();
    }

    /** 智能体统计 */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return decisionEngine.getStats();
    }

    /** 智能体「小七」档案 */
    @GetMapping("/profile")
    public Map<String, Object> getProfile() {
        return decisionEngine.getProfile();
    }

    /** 查询自动执行策略 */
    @GetMapping("/policies")
    public Map<String, Object> getPolicies() {
        List<AgentPolicy> policies = policyService.listPolicies();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("policies", policies);
        return result;
    }

    /**
     * 更新自动执行策略
     * body: {"updatedBy":"admin", "policies":{"ISOLATE":true,"DISCONNECT":false,...}}
     */
    @PutMapping("/policies")
    public Map<String, Object> updatePolicies(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        if (body == null) {
            result.put("success", false);
            result.put("message", "请求体为空");
            return result;
        }
        Object raw = body.get("policies");
        Map<String, Boolean> updates = new HashMap<>();
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                if (e.getKey() != null) {
                    updates.put(String.valueOf(e.getKey()), Boolean.valueOf(String.valueOf(e.getValue())));
                }
            }
        }
        String updatedBy = body.get("updatedBy") == null ? null : String.valueOf(body.get("updatedBy"));
        List<AgentPolicy> saved = policyService.updatePolicies(updates, updatedBy);
        result.put("success", true);
        result.put("message", "策略已更新，小七已按新策略值守");
        result.put("policies", saved);
        return result;
    }

    /** 与小七对话问答 */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> body) {
        String message = body == null ? "" : body.getOrDefault("message", "");
        return chatService.chat(message);
    }

    /** 执行决策 */
    @PutMapping("/actions/{id}/execute")
    public Map<String, Object> executeAction(@PathVariable Long id,
                                              @RequestParam(required = false) String note) {
        AgentAction action = decisionEngine.executeAction(id, note);
        Map<String, Object> result = new HashMap<>();
        if (action != null) {
            result.put("success", true);
            result.put("message", "决策已执行");
            result.put("action", action);
        } else {
            result.put("success", false);
            result.put("message", "决策不存在");
        }
        return result;
    }

    /** 驳回决策 */
    @PutMapping("/actions/{id}/reject")
    public Map<String, Object> rejectAction(@PathVariable Long id,
                                             @RequestParam(required = false) String note) {
        AgentAction action = decisionEngine.rejectAction(id, note);
        Map<String, Object> result = new HashMap<>();
        if (action != null) {
            result.put("success", true);
            result.put("message", "决策已驳回");
            result.put("action", action);
        } else {
            result.put("success", false);
            result.put("message", "决策不存在");
        }
        return result;
    }
}
