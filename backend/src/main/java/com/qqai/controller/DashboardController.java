package com.qqai.controller;

import com.qqai.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AstrBotConversationRepository conversationRepository;

    @Autowired
    private FileRecordRepository fileRecordRepository;

    @Autowired
    private AstrBotMessageRepository astrBotMessageRepository;

    @GetMapping("/stats")
    public ResponseEntity<?> getDashboardStats() {
        Map<String, Object> stats = new HashMap<>();

        // 消息统计
        long totalMessages = messageRepository.count();
        long todayMessages = 0;
        try {
            LocalDateTime todayStart = LocalDate.now().atStartOfDay();
            LocalDateTime todayEnd = todayStart.plusDays(1);
            Long count = messageRepository.countBySendTimeBetween(todayStart, todayEnd);
            todayMessages = count != null ? count : 0;
        } catch (Exception e) {
            // 忽略统计错误
        }

        // 群聊统计
        long totalGroups = groupRepository.count();
        long activeGroups = groupRepository.findByActiveTrue().size();

        // 用户统计
        long totalUsers = userRepository.count();

        // 对话统计
        long totalConversations = conversationRepository.count();
        long activeConversations = conversationRepository.findByArchivedFalseOrderByTimeUpdatedDesc().size();

        // 文件统计
        long totalFiles = fileRecordRepository.count();

        stats.put("totalMessages", totalMessages);
        stats.put("todayMessages", todayMessages);
        stats.put("totalGroups", totalGroups);
        stats.put("activeGroups", activeGroups);
        stats.put("totalUsers", totalUsers);
        stats.put("totalConversations", totalConversations);
        stats.put("activeConversations", activeConversations);
        stats.put("totalFiles", totalFiles);

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/message-trend")
    public ResponseEntity<?> getMessageTrend(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "day") String interval) {
        List<Map<String, Object>> trend = new ArrayList<>();

        if ("hour".equalsIgnoreCase(interval)) {
            // 按小时统计，返回最近24小时
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
            LocalDateTime now = LocalDateTime.now();
            for (int i = 23; i >= 0; i--) {
                LocalDateTime hourStart = now.minusHours(i).withMinute(0).withSecond(0).withNano(0);
                LocalDateTime hourEnd = hourStart.plusHours(1);

                long count = 0;
                try {
                    Long c = messageRepository.countBySendTimeBetween(hourStart, hourEnd);
                    count = c != null ? c : 0;
                } catch (Exception e) {
                    count = 0;
                }

                Map<String, Object> hourData = new HashMap<>();
                hourData.put("date", hourStart.format(formatter));
                hourData.put("count", count);
                trend.add(hourData);
            }
        } else {
            // 按天统计
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");
            if (days < 1) days = 7;
            if (days > 90) days = 90;

            for (int i = days - 1; i >= 0; i--) {
                LocalDate date = LocalDate.now().minusDays(i);
                LocalDateTime startOfDay = date.atStartOfDay();
                LocalDateTime endOfDay = date.plusDays(1).atStartOfDay();

                long count = 0;
                try {
                    Long c = messageRepository.countBySendTimeBetween(startOfDay, endOfDay);
                    count = c != null ? c : 0;
                } catch (Exception e) {
                    count = 0;
                }

                Map<String, Object> dayData = new HashMap<>();
                dayData.put("date", date.format(formatter));
                dayData.put("count", count);
                trend.add(dayData);
            }
        }

        return ResponseEntity.ok(trend);
    }

    @GetMapping("/group-ranking")
    public ResponseEntity<?> getGroupRanking() {
        // 返回消息最多的前10个群聊
        List<Map<String, Object>> ranking = new ArrayList<>();
        List<Object[]> recentGroups = messageRepository.findRecentGroups();

        for (Object[] row : recentGroups) {
            String groupId = (String) row[0];
            String groupName = (String) row[1];
            Long count = messageRepository.countActiveMessagesByGroupId(groupId);

            Map<String, Object> groupData = new HashMap<>();
            groupData.put("groupId", groupId);
            groupData.put("groupName", groupName != null ? groupName : "群聊 " + groupId);
            groupData.put("messageCount", count);
            ranking.add(groupData);
        }

        // 按消息数量降序排序，取前10
        ranking.sort((a, b) -> {
            Long countA = (Long) a.get("messageCount");
            Long countB = (Long) b.get("messageCount");
            return Long.compare(countB != null ? countB : 0, countA != null ? countA : 0);
        });

        if (ranking.size() > 10) {
            ranking = ranking.subList(0, 10);
        }

        return ResponseEntity.ok(ranking);
    }

    @GetMapping("/qq-ranking")
    public ResponseEntity<?> getQQRanking() {
        // 返回消息最多的前10个QQ账号
        List<Map<String, Object>> ranking = new ArrayList<>();
        try {
            List<Object[]> results = messageRepository.findTopQQByMessageCount();
            for (Object[] row : results) {
                String qq = row[0] != null ? row[0].toString() : "";
                String nickname = row[1] != null ? row[1].toString() : null;
                Long count = row[2] != null ? ((Number) row[2]).longValue() : 0L;

                Map<String, Object> qqData = new HashMap<>();
                qqData.put("qq", qq);
                qqData.put("nickname", nickname != null ? nickname : qq);
                qqData.put("messageCount", count);
                ranking.add(qqData);
            }
        } catch (Exception e) {
            // 忽略统计错误
        }
        return ResponseEntity.ok(ranking);
    }

    @GetMapping("/message-type-distribution")
    public ResponseEntity<?> getMessageTypeDistribution() {
        List<Map<String, Object>> distribution = new ArrayList<>();
        Map<String, Long> typeMap = new HashMap<>();

        try {
            List<Object[]> results = messageRepository.countByMessageType();
            for (Object[] row : results) {
                String typeName = row[0] != null ? row[0].toString() : "UNKNOWN";
                Long count = row[1] != null ? (Long) row[1] : 0L;
                typeMap.put(typeName, count);
            }
        } catch (Exception e) {
            // 忽略统计错误
        }

        String[] typeNames = {"TEXT", "IMAGE", "VIDEO", "FILE", "AUDIO", "VOICE", "OTHER"};
        String[] typeLabels = {"文本", "图片", "视频", "文件", "音频", "语音", "其他"};

        for (int i = 0; i < typeNames.length; i++) {
            Map<String, Object> item = new HashMap<>();
            item.put("type", typeLabels[i]);
            item.put("count", typeMap.getOrDefault(typeNames[i], 0L));
            distribution.add(item);
        }

        return ResponseEntity.ok(distribution);
    }
}
