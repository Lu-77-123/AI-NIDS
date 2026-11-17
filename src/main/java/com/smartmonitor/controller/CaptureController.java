package com.smartmonitor.controller;

import com.smartmonitor.service.PcapCaptureService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 旁路抓包状态与包级威胁查询。
 */
@RestController
@RequestMapping("/api/capture")
public class CaptureController {

    private final PcapCaptureService captureService;

    public CaptureController(PcapCaptureService captureService) {
        this.captureService = captureService;
    }

    /** 抓包运行状态、实时包速率、最近检出的包级威胁 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return captureService.getStats();
    }
}
