package com.qqai.controller;

import com.qqai.service.UserDashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 用户级 Dashboard 控制器
 * 所有接口返回的数据均按当前登录用户过滤，区别于全局 /api/dashboard/*。
 */
@RestController
@RequestMapping("/api/user/dashboard")
public class UserDashboardController {

    @Autowired
    private UserDashboardService userDashboardService;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(userDashboardService.getUserStats());
    }

    @GetMapping("/message-trend")
    public ResponseEntity<List<Map<String, Object>>> getMessageTrend(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "day") String interval) {
        return ResponseEntity.ok(userDashboardService.getMessageTrend(days, interval));
    }

    @GetMapping("/group-ranking")
    public ResponseEntity<List<Map<String, Object>>> getGroupRanking() {
        return ResponseEntity.ok(userDashboardService.getGroupRanking());
    }

    @GetMapping("/message-type-distribution")
    public ResponseEntity<List<Map<String, Object>>> getMessageTypeDistribution() {
        return ResponseEntity.ok(userDashboardService.getMessageTypeDistribution());
    }

    @GetMapping("/ai-trend")
    public ResponseEntity<List<Map<String, Object>>> getAiTrend() {
        return ResponseEntity.ok(userDashboardService.getAiTrend());
    }
}
