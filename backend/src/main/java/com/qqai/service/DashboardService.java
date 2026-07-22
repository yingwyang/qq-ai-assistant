package com.qqai.service;

import com.qqai.repository.AstrBotConversationRepository;
import com.qqai.repository.AstrBotMessageRepository;
import com.qqai.repository.FileRecordRepository;
import com.qqai.repository.GroupRepository;
import com.qqai.repository.MessageRepository;
import com.qqai.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class DashboardService {

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

    public Map<String, Object> getDashboardStats() {
        Map<String, Object> stats = new HashMap<>();

        long totalMessages = messageRepository.count();
        long todayMessages = 0;
        try {
            LocalDateTime todayStart = LocalDate.now().atStartOfDay();
            LocalDateTime todayEnd = todayStart.plusDays(1);
            Long count = messageRepository.countBySendTimeBetween(todayStart, todayEnd);
            todayMessages = count != null ? count : 0;
        } catch (Exception e) {
            // ignore
        }

        long totalGroups = groupRepository.count();
        long activeGroups = groupRepository.findByActiveTrue().size();
        long totalUsers = userRepository.count();
        long totalConversations = conversationRepository.count();
        long activeConversations = conversationRepository.findByArchivedFalseOrderByTimeUpdatedDesc().size();
        long totalFiles = fileRecordRepository.count();

        stats.put("totalMessages", totalMessages);
        stats.put("todayMessages", todayMessages);
        stats.put("totalGroups", totalGroups);
        stats.put("activeGroups", activeGroups);
        stats.put("totalUsers", totalUsers);
        stats.put("totalConversations", totalConversations);
        stats.put("activeConversations", activeConversations);
        stats.put("totalFiles", totalFiles);

        return stats;
    }

    public List<Map<String, Object>> getMessageTrend(int days, String interval) {
        List<Map<String, Object>> trend = new ArrayList<>();

        if ("hour".equalsIgnoreCase(interval)) {
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

        return trend;
    }

    public List<Map<String, Object>> getGroupRanking() {
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

        ranking.sort((a, b) -> {
            Long countA = (Long) a.get("messageCount");
            Long countB = (Long) b.get("messageCount");
            return Long.compare(countB != null ? countB : 0, countA != null ? countA : 0);
        });

        if (ranking.size() > 10) {
            return ranking.subList(0, 10);
        }
        return ranking;
    }

    public List<Map<String, Object>> getQQRanking() {
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
            // ignore
        }
        return ranking;
    }

    public List<Map<String, Object>> getMessageTypeDistribution() {
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
            // ignore
        }

        String[] typeNames = {"TEXT", "IMAGE", "VIDEO", "FILE", "AUDIO", "VOICE", "OTHER"};
        String[] typeLabels = {"文本", "图片", "视频", "文件", "音频", "语音", "其他"};

        for (int i = 0; i < typeNames.length; i++) {
            Map<String, Object> item = new HashMap<>();
            item.put("type", typeLabels[i]);
            item.put("count", typeMap.getOrDefault(typeNames[i], 0L));
            distribution.add(item);
        }

        return distribution;
    }
}
