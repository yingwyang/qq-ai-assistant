package com.qqai.service;

import com.qqai.repository.AstrBotConversationRepository;
import com.qqai.repository.AstrBotMessageRepository;
import com.qqai.repository.FileRecordRepository;
import com.qqai.repository.GroupRepository;
import com.qqai.repository.MessageRepository;
import com.qqai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

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
        return getDashboardStats(7);
    }

    /**
     * 概览统计。
     *
     * @param days 时间范围（7/30/90 天），用于 range* 系列字段；非法值回落 7
     */
    public Map<String, Object> getDashboardStats(int days) {
        Map<String, Object> stats = new HashMap<>();

        int rangeDays = normalizeDays(days);
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();
        LocalDateTime rangeStart = today.minusDays(rangeDays - 1L).atStartOfDay();

        long totalMessages = messageRepository.count();
        long todayMessages = 0;
        try {
            Long count = messageRepository.countBySendTimeBetween(todayStart, tomorrowStart);
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
        // 如果 file_records 为空（媒体下载流程未写入），回退统计 uploads 目录实际文件数
        if (totalFiles == 0) {
            totalFiles = countUploadFiles();
        }

        long todayActiveUsers = 0;
        try {
            Long count = messageRepository.countActiveUsersBetween(todayStart, tomorrowStart);
            todayActiveUsers = count != null ? count : 0;
        } catch (Exception e) {
            // ignore
        }

        // 区间统计（概览的时间范围选择器驱动）
        long rangeMessages = 0;
        long rangeActiveUsers = 0;
        try {
            Long count = messageRepository.countBySendTimeBetween(rangeStart, tomorrowStart);
            rangeMessages = count != null ? count : 0;
        } catch (Exception e) {
            // ignore
        }
        try {
            Long count = messageRepository.countActiveUsersBetween(rangeStart, tomorrowStart);
            rangeActiveUsers = count != null ? count : 0;
        } catch (Exception e) {
            // ignore
        }

        // 近 7 天 AI 消息：字段名以前叫 todayAiConversations，实际统计的是 7 天，
        // 命名与语义不符会让页面上出现「今日新增 1323」而总量只有几十的怪现象。
        long weekAiMessages = 0;
        try {
            LocalDateTime weekStart = today.minusDays(6).atStartOfDay();
            long weekAiMsgs = astrBotMessageRepository.countByTimeCreatedBetween(weekStart, tomorrowStart);
            weekAiMessages = weekAiMsgs;
        } catch (Exception e) {
            // ignore
        }

        long rangeAiMessages = 0;
        try {
            rangeAiMessages = astrBotMessageRepository.countByTimeCreatedBetween(rangeStart, tomorrowStart);
        } catch (Exception e) {
            // ignore
        }

        long totalAiMessages = astrBotMessageRepository.count();

        stats.put("totalMessages", totalMessages);
        stats.put("todayMessages", todayMessages);
        stats.put("totalGroups", totalGroups);
        stats.put("activeGroups", activeGroups);
        stats.put("totalUsers", totalUsers);
        stats.put("totalConversations", totalConversations);
        stats.put("activeConversations", activeConversations);
        stats.put("totalFiles", totalFiles);
        stats.put("todayActiveUsers", todayActiveUsers);
        // 保留旧字段名（附加上 weekAiMessages，避免破坏其它调用方）
        stats.put("todayAiConversations", weekAiMessages);
        stats.put("weekAiMessages", weekAiMessages);
        stats.put("totalAiMessages", totalAiMessages);
        stats.put("rangeDays", rangeDays);
        stats.put("rangeMessages", rangeMessages);
        stats.put("rangeActiveUsers", rangeActiveUsers);
        stats.put("rangeAiMessages", rangeAiMessages);
        stats.put("rangeStart", rangeStart.toLocalDate().toString());

        return stats;
    }

    /** 时间范围白名单：只允许 7 / 30 / 90，其余一律回落到 7 */
    private int normalizeDays(int days) {
        if (days == 30 || days == 90) return days;
        return 7;
    }

    private LocalDateTime rangeStart(int days) {
        return LocalDate.now().minusDays(normalizeDays(days) - 1L).atStartOfDay();
    }

    private LocalDateTime rangeEnd() {
        return LocalDate.now().plusDays(1).atStartOfDay();
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
        return getGroupRanking(0);
    }

    /**
     * 群消息排行。
     *
     * @param days 0 表示全量（历史行为）；7/30/90 时按区间统计
     */
    public List<Map<String, Object>> getGroupRanking(int days) {
        List<Map<String, Object>> ranking = new ArrayList<>();
        List<Object[]> recentGroups;
        boolean ranged = normalizeDaysOrZero(days) > 0;
        if (ranged) {
            recentGroups = messageRepository.findGroupRankingBetween(
                    rangeStart(days), rangeEnd(), org.springframework.data.domain.PageRequest.of(0, 10));
        } else {
            recentGroups = messageRepository.findRecentGroups();
        }

        for (Object[] row : recentGroups) {
            String groupId = row[0] != null ? row[0].toString() : "";
            String groupName = row[1] != null ? row[1].toString() : null;
            long count = ranged
                    ? (row[2] != null ? ((Number) row[2]).longValue() : 0L)
                    : safeCount(messageRepository.countActiveMessagesByGroupId(groupId));

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

    private long safeCount(Long value) {
        return value != null ? value : 0L;
    }

    /** 0（全量）或 7/30/90（区间）；其它非法值按全量处理 */
    private int normalizeDaysOrZero(int days) {
        if (days == 7 || days == 30 || days == 90) return days;
        return 0;
    }

    public List<Map<String, Object>> getQQRanking() {
        return getQQRanking(0);
    }

    /**
     * 发言用户排行。
     *
     * @param days 0 表示全量（历史行为）；7/30/90 时按区间统计
     */
    public List<Map<String, Object>> getQQRanking(int days) {
        List<Map<String, Object>> ranking = new ArrayList<>();
        try {
            List<Object[]> results = normalizeDaysOrZero(days) > 0
                    ? messageRepository.findTopQQBetween(rangeStart(days), rangeEnd(),
                            org.springframework.data.domain.PageRequest.of(0, 10))
                    : messageRepository.findTopQQByMessageCount();
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
        return getMessageTypeDistribution(0);
    }

    /** 消息类型分布；days 为 0 时全量，7/30/90 时按区间统计 */
    public List<Map<String, Object>> getMessageTypeDistribution(int days) {
        List<Map<String, Object>> distribution = new ArrayList<>();
        Map<String, Long> typeMap = new HashMap<>();

        try {
            List<Object[]> results = normalizeDaysOrZero(days) > 0
                    ? messageRepository.countByMessageTypeBetween(rangeStart(days), rangeEnd())
                    : messageRepository.countByMessageType();
            for (Object[] row : results) {
                String typeName = row[0] != null ? row[0].toString() : "UNKNOWN";
                Long count = row[1] != null ? ((Number) row[1]).longValue() : 0L;
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

    /**
     * 获取今日每小时消息分布（柱状图）
     */
    public List<Map<String, Object>> getHourlyDistribution() {
        return getHourlyDistribution(0);
    }

    /**
     * 小时分布。
     *
     * @param days 0 表示仅今天（历史行为）；7/30/90 时把区间内消息按小时聚合
     */
    public List<Map<String, Object>> getHourlyDistribution(int days) {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate today = LocalDate.now();

        int[] hourCounts = new int[24];
        try {
            List<Object[]> rows = normalizeDaysOrZero(days) > 0
                    ? messageRepository.countByHourBetween(rangeStart(days), rangeEnd())
                    : messageRepository.countByHour(today);
            for (Object[] row : rows) {
                int hour = ((Number) row[0]).intValue();
                long count = row[1] != null ? ((Number) row[1]).longValue() : 0L;
                if (hour >= 0 && hour < 24) {
                    hourCounts[hour] = (int) count;
                }
            }
        } catch (Exception e) {
            // ignore
        }

        for (int h = 0; h < 24; h++) {
            Map<String, Object> item = new HashMap<>();
            item.put("hour", String.format("%02d", h));
            item.put("count", hourCounts[h]);
            result.add(item);
        }
        return result;
    }

    /**
     * 获取 AI 对话消息按天趋势（默认近 7 天）
     */
    public List<Map<String, Object>> getAiTrend() {
        return getAiTrend(7);
    }

    public List<Map<String, Object>> getAiTrend(int days) {
        int rangeDays = normalizeDays(days);
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.minusDays(rangeDays - 1L).atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();

        Map<String, Long> dayMap = new HashMap<>();
        try {
            List<Object[]> rows = astrBotMessageRepository.countDailyBetween(start, end);
            for (Object[] row : rows) {
                // 这里是原生 DATE_FORMAT 查询，返回的是字符串而不是 Date；
                // 用 DateKeys 归一化，避免硬转型 ClassCastException 把整段时间线清零。
                String key = com.qqai.util.DateKeys.toIsoDate(row[0]);
                if (key == null) {
                    log.warn("AI 趋势：无法识别的日期值 {}，已跳过该行", row[0]);
                    continue;
                }
                long count = row[1] != null ? ((Number) row[1]).longValue() : 0L;
                dayMap.put(key, count);
            }
        } catch (Exception e) {
            // 以前这里是空 catch，图表静默变成全 0；至少留下日志，别再让问题无声无息
            log.warn("AI 对话趋势统计失败，将返回全 0 序列: {}", e.toString());
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        for (int i = rangeDays - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            String key = date.toString();
            Map<String, Object> item = new HashMap<>();
            item.put("date", date.format(fmt));
            item.put("count", dayMap.getOrDefault(key, 0L));
            result.add(item);
        }
        return result;
    }

    /**
     * 统计 uploads 目录下的实际文件数（回退方案，当 file_records 表为空时使用）。
     */
    private long countUploadFiles() {
        String projectRoot = System.getProperty("user.dir");
        File uploadsDir = new File(projectRoot, "uploads");
        if (!uploadsDir.exists() || !uploadsDir.isDirectory()) {
            return 0;
        }
        return countFilesRecursive(uploadsDir);
    }

    private long countFilesRecursive(File dir) {
        long count = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            if (file.isDirectory()) {
                count += countFilesRecursive(file);
            } else {
                count++;
            }
        }
        return count;
    }
}
