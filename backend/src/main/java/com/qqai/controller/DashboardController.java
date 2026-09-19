package com.qqai.controller;

import com.qqai.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 数据概览接口。
 *
 * <p>{@code days} 约定：7 / 30 / 90 表示时间范围；0 表示「全量」（各图表的历史行为）。
 * 概览页顶部的时间范围选择器会把 days 传给这里的所有接口，让卡片与图表口径一致 ——
 * 改造前只有 message-trend 接受 days，其余图表永远是全量，切换时间范围时页面自相矛盾。</p>
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired
    private DashboardService dashboardService;

    @GetMapping("/stats")
    public ResponseEntity<?> getDashboardStats(@RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(dashboardService.getDashboardStats(days));
    }

    @GetMapping("/message-trend")
    public ResponseEntity<?> getMessageTrend(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "day") String interval) {
        return ResponseEntity.ok(dashboardService.getMessageTrend(days, interval));
    }

    @GetMapping("/group-ranking")
    public ResponseEntity<?> getGroupRanking(@RequestParam(defaultValue = "0") int days) {
        return ResponseEntity.ok(dashboardService.getGroupRanking(days));
    }

    @GetMapping("/qq-ranking")
    public ResponseEntity<?> getQQRanking(@RequestParam(defaultValue = "0") int days) {
        return ResponseEntity.ok(dashboardService.getQQRanking(days));
    }

    @GetMapping("/message-type-distribution")
    public ResponseEntity<?> getMessageTypeDistribution(@RequestParam(defaultValue = "0") int days) {
        return ResponseEntity.ok(dashboardService.getMessageTypeDistribution(days));
    }

    @GetMapping("/hourly-distribution")
    public ResponseEntity<?> getHourlyDistribution(@RequestParam(defaultValue = "0") int days) {
        return ResponseEntity.ok(dashboardService.getHourlyDistribution(days));
    }

    @GetMapping("/ai-trend")
    public ResponseEntity<?> getAiTrend(@RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(dashboardService.getAiTrend(days));
    }
}
