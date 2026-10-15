package com.qqai;

import com.qqai.entity.Message;
import com.qqai.repository.MessageRepository;
import com.qqai.service.MessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 群消息有界取数测试。
 *
 * <p>背景：{@code /api/messages/group/{id}} 与 AI 分析原先都是「整群历史全量返回」，
 * 超大群会把整个结果集拉进内存（OOM）。改为有界查询后，这里钉住两点：</p>
 * <ol>
 *   <li><b>取的是最近 N 条</b>（不是最旧的 N 条——查询按 serverRecvMs 倒序，取错方向会让 AI 分析与群类型识别用到过期样本）；</li>
 *   <li><b>条数被夹在 [1, MAX]</b>，客户端传 0 / 超大值都不会把内存打爆。</li>
 * </ol>
 */
@SpringBootTest
class MessageFetchBoundTest {

    private static final String GROUP_ID = "900100200";
    private static final String QQ = "10001";

    @Autowired
    private MessageService messageService;

    @Autowired
    private MessageRepository messageRepository;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        createdIds.forEach(id -> messageRepository.findById(id).ifPresent(messageRepository::delete));
        createdIds.clear();
    }

    @Test
    @DisplayName("只取最近 N 条：返回的是最新样本，且不超过请求条数")
    void returnsNewestMessagesWithinLimit() {
        insertMessages(10);

        List<Message> recent = messageService.getRecentMessages(GROUP_ID, List.of(QQ), 3);

        assertEquals(3, recent.size(), "应按 limit 截断");
        // serverRecvMs 越大越新：最新的三条是 10/9/8
        assertEquals(List.of(10L, 9L, 8L),
                recent.stream().map(Message::getServerRecvMs).toList(),
                "必须返回最近的样本，而不是最旧的（方向取反会让分析用到过期内容）");
    }

    @Test
    @DisplayName("limit<=0 收敛为 1，不会返回空或整群历史")
    void limitBelowOneIsClampedToOne() {
        insertMessages(5);
        assertEquals(1, messageService.getRecentMessages(GROUP_ID, List.of(QQ), 0).size());
        assertEquals(1, messageService.getRecentMessages(GROUP_ID, List.of(QQ), -100).size());
    }

    @Test
    @DisplayName("limit 超大时被夹到硬上限，防止单次把整群历史读进内存")
    void limitAboveMaxIsClampedToUpperBound() {
        int total = MessageService.MAX_GROUP_MESSAGE_FETCH + 5;
        insertMessages(total);

        List<Message> result = messageService.getRecentMessages(GROUP_ID, List.of(QQ), 1_000_000);

        assertEquals(MessageService.MAX_GROUP_MESSAGE_FETCH, result.size(),
                "超过硬上限的请求必须被夹住，否则大群仍会 OOM");
    }

    @Test
    @DisplayName("AI 分析取样：排除已删除消息，且仍受上限约束")
    void recentActiveMessagesSkipDeleted() {
        insertMessages(5);
        Message newest = messageRepository
                .findRecentByGroupIdAndSelfQq(GROUP_ID, QQ, org.springframework.data.domain.PageRequest.of(0, 1))
                .get(0);
        newest.setDeleted(true);
        messageRepository.save(newest);

        List<Message> active = messageService.getRecentActiveMessages(GROUP_ID, 10);

        assertEquals(4, active.size(), "已软删除的消息不应进入分析样本");
        assertTrue(active.stream().noneMatch(Message::isDeleted));
    }

    @Test
    @DisplayName("按 ID 批量取消息：一次 IN 查询取回全部（替代逐个查询的 N+1）")
    void batchFetchByIdsReturnsAllRequested() {
        insertMessages(5);
        List<Long> ids = createdIds.subList(0, 3);

        List<Message> fetched = messageService.getMessagesByIds(List.of(ids.get(2), ids.get(0), ids.get(1)));

        assertEquals(3, fetched.size(), "批量取数应取回全部请求的 ID");
        assertEquals(3, Set.copyOf(fetched.stream().map(Message::getId).toList()).size(), "不应有重复或遗漏");
    }

    @Test
    @DisplayName("按 ID 批量取数：空入参返回空列表，不查库")
    void batchFetchWithEmptyIds() {
        assertTrue(messageService.getMessagesByIds(List.of()).isEmpty());
        assertTrue(messageService.getMessagesByIds(null).isEmpty());
    }

    @Test
    @DisplayName("清理媒体时的引用查询按群收窄：只扫该群，不再全表 LIKE")
    void contentPathQueryIsScopedToGroup() {
        String path = "image/900100200/2026-10-15/x.jpg";
        insertMessageWithContent("900100200", path);                 // 同群命中
        insertMessageWithContent("900999999", "/" + path);           // 其它群的同形内容不应被扫到

        List<Message> scoped = messageRepository
                .findByGroupIdAndContentContainingPath("900100200", path, "/" + path);
        assertEquals(1, scoped.size(), "按群收窄后只应命中本群那条");

        // 对照组：不带群号过滤的老查询会同时扫到两个群（这正是要避免的全表扫描行为）
        List<Message> unscoped = messageRepository.findByContentContainingPath(path, "/" + path);
        assertEquals(2, unscoped.size(), "老查询的扫描范围更大（对照组）");
    }

    // ===================== 辅助方法 =====================

    /** 插入一条指定群、指定内容的文本消息 */
    private Message insertMessageWithContent(String groupId, String content) {
        Message m = new Message();
        m.setGroupId(groupId);
        m.setMessageId("bound-path-" + groupId + "-" + createdIds.size());
        m.setUserQq(QQ);
        m.setContent(content);
        m.setSelfQq(QQ);
        m.setServerRecvMs((long) createdIds.size());
        m.setSendTime(LocalDateTime.now());
        Message saved = messageRepository.save(m);
        createdIds.add(saved.getId());
        return saved;
    }

    /** 插入 count 条消息，serverRecvMs 从 1 递增到 count（值越大越新） */
    private void insertMessages(int count) {
        List<Message> batch = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            Message m = new Message();
            m.setGroupId(GROUP_ID);
            m.setMessageId("bound-test-" + i);
            m.setUserQq(QQ);
            m.setUserNickname("tester");
            m.setContent("msg-" + i);
            m.setSelfQq(QQ);
            m.setServerRecvMs((long) i);
            m.setSendTime(LocalDateTime.now().minusSeconds(count - i));
            batch.add(m);
        }
        messageRepository.saveAll(batch).forEach(m -> createdIds.add(m.getId()));
    }
}
