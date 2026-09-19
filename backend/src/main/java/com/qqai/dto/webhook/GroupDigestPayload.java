package com.qqai.dto.webhook;

import java.io.Serializable;

/**
 * 群日报任务载荷。
 *
 * <p>投递方：{@code GroupController#generateGroupDigestAsync}（手动异步）与
 * {@code GroupDigestScheduler}（定时日报）；消费方：{@code GroupDigestConsumer} →
 * {@code GroupDigestService#generateDailyDigest}。</p>
 *
 * <p>{@code date} 用字符串（ISO-8601，如 {@code 2026-09-16}）而不是 {@code LocalDate}：
 * 跨队列只传最朴素的结构、由消费端解析，避免依赖 Jackson 的 JavaTimeModule 配置。
 * 空串/null = 今天，与同步接口的语义一致。</p>
 */
public class GroupDigestPayload implements Serializable {

    /** 群号 */
    private String groupId;

    /** 归属日期（ISO-8601 字符串，可空 = 今天） */
    private String date;

    /** 是否强制重算（true = 当天已有日报也重新调用大模型） */
    private Boolean force;

    /** 触发者用户 id（「人层」用他选定的 AstrBot 人格）；定时任务为 null = 用默认 */
    private Long requestedBy;

    public GroupDigestPayload() {
    }

    public GroupDigestPayload(String groupId, String date, Boolean force) {
        this(groupId, date, force, null);
    }

    public GroupDigestPayload(String groupId, String date, Boolean force, Long requestedBy) {
        this.groupId = groupId;
        this.date = date;
        this.force = force;
        this.requestedBy = requestedBy;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public Boolean getForce() {
        return force;
    }

    public void setForce(Boolean force) {
        this.force = force;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(Long requestedBy) {
        this.requestedBy = requestedBy;
    }

    @Override
    public String toString() {
        return "GroupDigestPayload{groupId=" + groupId + ", date=" + date + ", force=" + force
                + ", requestedBy=" + requestedBy + '}';
    }
}
