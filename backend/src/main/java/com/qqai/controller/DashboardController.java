package com.qqai.controller;

import com.qqai.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired
    private DashboardService dashboardService;

    @GetMapping("/stats")
    public ResponseEntity<?> getDashboardStats() {
        return ResponseEntity.ok(dashboardService.getDashboardStats());
    }

    @GetMapping("/message-trend")
    public ResponseEntity<?> getMessageTrend(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "day") String interval) {
        return ResponseEntity.ok(dashboardService.getMessageTrend(days, interval));
    }

    @GetMapping("/group-ranking")
    public ResponseEntity<?> getGroupRanking() {
        return ResponseEntity.ok(dashboardService.getGroupRanking());
    }

    @GetMapping("/qq-ranking")
    public ResponseEntity<?> getQQRanking() {
        return ResponseEntity.ok(dashboardService.getQQRanking());
    }

    @GetMapping("/message-type-distribution")
    public ResponseEntity<?> getMessageTypeDistribution() {
        return ResponseEntity.ok(dashboardService.getMessageTypeDistribution());
    }
}
