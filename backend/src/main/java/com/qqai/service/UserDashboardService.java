package com.qqai.service;

import com.qqai.common.SecurityHelper;
import com.qqai.entity.AstrBotConversation;
import com.qqai.entity.Group;
import com.qqai.repository.AstrBotConversationRepository;
import com.qqai.repository.AstrBotMessageRepository;
import com.qqai.repository.FileRecordRepository;
import com.qqai.repository.GroupRepository;
import com.qqai.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class UserDashboardService {

    private static final Logger log = LoggerFactory.getLogger(UserDashboardService.class);

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private FileRecordRepository fileRecordRepository;

    @Autowired
    private AstrBotConversationRepository conversationRepository;

    @Autowired
    private AstrBotMessageRepository astrBotMessageRepository;

    public Map<String, Object> getUserStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalMessages", 0L);
        stats.put("todayMessages", 0L);
        stats.put("totalGroups", 0L);
        stats.put("activeGroups", 0L);
        stats.put("totalConversations", 0L);
        stats.put("totalFiles", 0L);
        stats.put("filesSize", 0L);
        stats.put("filesSizeFormatted", formatBytes(0L));

        try {
            Long userId = securityHelper.getCurrentUserId();
            List<String> selfQqList = securityHelper.getCurrentUserQqBindings();
            boolean hasQq = selfQqList != null && !selfQqList.isEmpty();

            LocalDate today = LocalDate.now();
            LocalDateTime todayStart = today.atStartOfDay();
            LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();

            long totalMessages = 0;
            long todayMessages = 0;
            long totalGroups = 0;
            long activeGroups = 0;
            if (hasQq) {
                try {
                    totalMessages = messageRepository.countBySelfQqIn(selfQqList);
                } catch (Exception e) {
                    totalMessages = 0L;
                }
                try {
                    todayMessages = messageRepository.countBySendTimeBetweenAndSelfQqIn(todayStart, tomorrowStart, selfQqList);
                } catch (Exception e) {
                    todayMessages = 0L;
                }
                try {
                    totalGroups = groupRepository.countByOwnerQqIn(selfQqList);
                } catch (Exception e) {
                    totalGroups = 0L;
                }
                try {
                    activeGroups = groupRepository.countByOwnerQqInAndActiveTrue(selfQqList);
                } catch (Exception e) {
                    activeGroups = 0L;
                }
            }

            long totalConversations = 0;
            long totalFiles = 0;
            long filesSize = 0;
            if (userId != null) {
                try {
                    totalConversations = conversationRepository.countByUserId(userId);
                } catch (Exception e) {
                    totalConversations = 0L;
                }
                try {
                    totalFiles = fileRecordRepository.countByUploaderId(userId);
                } catch (Exception e) {
                    totalFiles = 0L;
                }
                try {
                    Long size = fileRecordRepository.sumFileSizeByUploaderId(userId);
                    filesSize = size != null ? size : 0L;
                } catch (Exception e) {
                    filesSize = 0L;
                }
            }

            stats.put("totalMessages", totalMessages);
            stats.put("todayMessages", todayMessages);
            stats.put("totalGroups", totalGroups);
            stats.put("activeGroups", activeGroups);
            stats.put("totalConversations", totalConversations);
            stats.put("totalFiles", totalFiles);
            stats.put("filesSize", filesSize);
            stats.put("filesSizeFormatted", formatBytes(filesSize));
        } catch (Exception e) {
            stats.put("error", e.getMessage());
        }

        return stats;
    }

    public List<Map<String, Object>> getMessageTrend(int days, String interval) {
        List<Map<String, Object>> trend = new ArrayList<>();

        try {
            List<String> selfQqList = securityHelper.getCurrentUserQqBindings();
            boolean hasQq = selfQqList != null && !selfQqList.isEmpty();

            if ("hour".equalsIgnoreCase(interval)) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
                LocalDateTime now = LocalDateTime.now();
                for (int i = 23; i >= 0; i--) {
                    LocalDateTime hourStart = now.minusHours(i).withMinute(0).withSecond(0).withNano(0);
                    LocalDateTime hourEnd = hourStart.plusHours(1);

                    long count = 0;
                    if (hasQq) {
                        try {
                            count = messageRepository.countBySendTimeBetweenAndSelfQqIn(hourStart, hourEnd, selfQqList);
                        } catch (Exception e) {
                            count = 0;
                        }
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
                    if (hasQq) {
                        try {
                            count = messageRepository.countBySendTimeBetweenAndSelfQqIn(startOfDay, endOfDay, selfQqList);
                        } catch (Exception e) {
                            count = 0;
                        }
                    }

                    Map<String, Object> dayData = new HashMap<>();
                    dayData.put("date", date.format(formatter));
                    dayData.put("count", count);
                    trend.add(dayData);
                }
            }
        } catch (Exception e) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");
            for (int i = 6; i >= 0; i--) {
                LocalDate date = LocalDate.now().minusDays(i);
                Map<String, Object> dayData = new HashMap<>();
                dayData.put("date", date.format(formatter));
                dayData.put("count", 0L);
                trend.add(dayData);
            }
        }

        return trend;
    }

    public List<Map<String, Object>> getGroupRanking() {
        List<Map<String, Object>> ranking = new ArrayList<>();

        try {
            List<String> selfQqList = securityHelper.getCurrentUserQqBindings();
            if (selfQqList == null || selfQqList.isEmpty()) {
                return ranking;
            }

            List<String> groupIds;
            try {
                groupIds = messageRepository.findGroupIdsBySelfQqIn(selfQqList);
            } catch (Exception e) {
                return ranking;
            }
            if (groupIds == null || groupIds.isEmpty()) {
                return ranking;
            }

            for (String groupId : groupIds) {
                Long count = 0L;
                try {
                    Long c = messageRepository.countActiveMessagesByGroupIdAndSelfQqIn(groupId, selfQqList);
                    count = c != null ? c : 0L;
                } catch (Exception e) {
                    count = 0L;
                }

                String groupName = "群聊 " + groupId;
                try {
                    List<Group> groups = groupRepository.findByGroupId(groupId);
                    if (groups != null && !groups.isEmpty() && groups.get(0).getGroupName() != null) {
                        groupName = groups.get(0).getGroupName();
                    }
                } catch (Exception e) {
                    groupName = "群聊 " + groupId;
                }

                Map<String, Object> groupData = new HashMap<>();
                groupData.put("groupId", groupId);
                groupData.put("groupName", groupName);
                groupData.put("messageCount", count);
                ranking.add(groupData);
            }

            ranking.sort((a, b) -> {
                Long countA = (Long) a.get("messageCount");
                Long countB = (Long) b.get("messageCount");
                return Long.compare(countB != null ? countB : 0, countA != null ? countA : 0);
            });

            if (ranking.size() > 5) {
                return ranking.subList(0, 5);
            }
        } catch (Exception e) {
            return ranking;
        }

        return ranking;
    }

    public List<Map<String, Object>> getMessageTypeDistribution() {
        List<Map<String, Object>> distribution = new ArrayList<>();
        Map<String, Long> typeMap = new HashMap<>();

        try {
            List<String> selfQqList = securityHelper.getCurrentUserQqBindings();
            if (selfQqList != null && !selfQqList.isEmpty()) {
                try {
                    List<Object[]> results = messageRepository.countByMessageTypeAndSelfQqIn(selfQqList);
                    if (results != null) {
                        for (Object[] row : results) {
                            String typeName = row[0] != null ? row[0].toString() : "UNKNOWN";
                            Long count = row[1] != null ? ((Number) row[1]).longValue() : 0L;
                            typeMap.put(typeName, count);
                        }
                    }
                } catch (Exception e) {
                    typeMap.clear();
                }
            }
        } catch (Exception e) {
            typeMap.clear();
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

    public List<Map<String, Object>> getAiTrend() {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        Map<String, Long> dayMap = new HashMap<>();

        try {
            Long userId = securityHelper.getCurrentUserId();

            if (userId != null) {
                List<String> conversationIds = new ArrayList<>();
                try {
                    List<AstrBotConversation> conversations = conversationRepository.findByUserIdOrderByTimeUpdatedDesc(userId);
                    if (conversations != null) {
                        for (AstrBotConversation c : conversations) {
                            if (c != null && c.getConversationId() != null && !c.getConversationId().isBlank()) {
                                conversationIds.add(c.getConversationId());
                            }
                        }
                    }
                } catch (Exception e) {
                    conversationIds.clear();
                }

                if (!conversationIds.isEmpty()) {
                    LocalDateTime start = today.minusDays(6).atStartOfDay();
                    LocalDateTime end = today.plusDays(1).atStartOfDay();
                    try {
                        List<Object[]> rows = astrBotMessageRepository.countDailyByConversationIdInAndTimeCreatedBetween(
                                conversationIds, start, end);
                        if (rows != null) {
                            for (Object[] row : rows) {
                                // 聚合查询的日期列类型随数据库/方言变化，统一归一化成 MM-dd
                                String dayKey = com.qqai.util.DateKeys.toMonthDay(row[0]);
                                long count = row[1] != null ? ((Number) row[1]).longValue() : 0L;
                                if (dayKey != null) {
                                    dayMap.put(dayKey, count);
                                }
                            }
                        }
                    } catch (Exception e) {
                        // 以前这里静默清空 → 图表变成全 0 也无人发现，至少留一条日志
                        log.warn("用户 AI 对话趋势统计失败，将返回全 0 序列: {}", e.toString());
                        dayMap.clear();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("用户 AI 对话趋势统计失败，将返回全 0 序列: {}", e.toString());
            dayMap.clear();
        }

        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            String key = date.format(fmt);
            Map<String, Object> item = new HashMap<>();
            item.put("date", key);
            item.put("count", dayMap.getOrDefault(key, 0L));
            result.add(item);
        }
        return result;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
