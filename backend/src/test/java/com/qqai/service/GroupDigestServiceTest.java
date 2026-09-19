package com.qqai.service;

import com.qqai.entity.GroupDigest;
import com.qqai.entity.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 群日报（AI 摘要阶段 3）纯逻辑测试。
 *
 * <p>只覆盖不依赖 Spring 上下文的部分：输入拼接/截断、模型输出解析、提示词。
 * 取数（MessageRepository）与大模型调用（AstrBotService）在集成环境验证，这里不 mock。</p>
 */
class GroupDigestServiceTest {

    private final GroupDigestService service = new GroupDigestService();

    private static Message textMessage(String nickname, String qq, String content) {
        Message m = new Message();
        m.setGroupId("808521399");
        m.setUserNickname(nickname);
        m.setUserQq(qq);
        m.setContent(content);
        m.setMessageType(Message.MessageType.TEXT);
        return m;
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    @Test
    @DisplayName("单条消息按「昵称: 内容」拼接，且逐条截断到 200 字符")
    void shouldTruncateEachMessageTo200Chars() {
        Message longOne = textMessage("小明", "10001", repeat('a', 500));
        Message shortOne = textMessage("小红", "10002", "今天有人开黑吗");

        String input = service.buildDigestInput(List.of(longOne, shortOne));
        String[] lines = input.split("\n");

        assertEquals(2, lines.length);
        assertEquals(GroupDigestService.PER_MESSAGE_MAX_CHARS, lines[0].length());
        assertTrue(lines[0].startsWith("小明: aaaa"));
        assertEquals("小红: 今天有人开黑吗", lines[1]);
    }

    @Test
    @DisplayName("图片/视频消息的本地路径替换为可读占位符，不把 /images/… 当正文喂给模型")
    void shouldReplaceLocalMediaPathsWithPlaceholders() {
        Message image = textMessage("小明", "10001", "/images/images/674405515/2026-09-19/abc.jpg");
        image.setMessageType(Message.MessageType.IMAGE);
        Message video = textMessage("小红", "10002", "/images/video/1.mp4");
        video.setMessageType(Message.MessageType.VIDEO);
        Message normalText = textMessage("小刚", "10003", "看图说话 /images/not-a-media-path");

        String input = service.buildDigestInput(List.of(image, video, normalText));
        String[] lines = input.split("\n");

        assertEquals("小明: [图片]", lines[0]);
        assertEquals("小红: [视频]", lines[1]);
        // 非媒体后缀的文本原样保留（避免误伤正常内容）
        assertEquals("小刚: 看图说话 /images/not-a-media-path", lines[2]);
    }

    @Test
    @DisplayName("总长超 12000 字符时保留前 6000 + 中间省略 + 保留后 6000")
    void shouldKeepBothEndsWhenInputTooLong() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            messages.add(textMessage("u", "10001", repeat('a', 250)));
        }
        // 未截断前每行 = "u: " + 250 个 a → 截断到 200 字符，100 行合计 20099 字符
        String line = ("u: " + repeat('a', 250)).substring(0, GroupDigestService.PER_MESSAGE_MAX_CHARS);
        String full = String.join("\n", java.util.Collections.nCopies(100, line));
        assertTrue(full.length() > GroupDigestService.MAX_INPUT_CHARS);

        String input = service.buildDigestInput(messages);

        assertTrue(input.contains("中间省略"), "应在中间插入省略标记");
        assertEquals(full.substring(0, GroupDigestService.KEEP_CHARS_PER_SIDE), input.substring(0, GroupDigestService.KEEP_CHARS_PER_SIDE));
        assertEquals(full.substring(full.length() - GroupDigestService.KEEP_CHARS_PER_SIDE),
                input.substring(input.length() - GroupDigestService.KEEP_CHARS_PER_SIDE));
        assertTrue(input.length() > GroupDigestService.MAX_INPUT_CHARS, "省略标记本身会让结果略长于上限");
    }

    @Test
    @DisplayName("未超长时输入原样保留，不做省略处理")
    void shouldNotClipShortInput() {
        String shortInput = "小明: 今天有人开黑吗";
        assertEquals(shortInput, service.clipMiddle(shortInput));
        assertEquals("", service.clipMiddle(null));
    }

    @Test
    @DisplayName("空消息列表返回空字符串")
    void shouldReturnEmptyForNoMessages() {
        assertEquals("", service.buildDigestInput(null));
        assertEquals("", service.buildDigestInput(List.of()));
        // 内容为空/空白的消息不参与拼接
        assertEquals("", service.buildDigestInput(List.of(textMessage("小明", "1", "   "))));
    }

    @Test
    @DisplayName("解析带 ```json 围栏的模型输出")
    void shouldParseFencedJsonOutput() {
        GroupDigest digest = new GroupDigest();
        digest.setGroupId("808521399");
        String raw = "```json\n{\"summary\":\"今天主要在约开黑\",\"tags\":[\"组队\",\"开黑\"],\"sentiment\":\"positive\"}\n```";

        service.applyModelOutput(digest, raw);

        assertEquals("今天主要在约开黑", digest.getSummary());
        assertEquals("组队,开黑", digest.getTags());
        assertEquals("positive", digest.getSentiment());
    }

    @Test
    @DisplayName("解析裸 JSON 输出（无围栏）")
    void shouldParsePlainJsonOutput() {
        GroupDigest digest = new GroupDigest();
        String raw = "{\"summary\":\"讨论了版本更新\",\"tags\":[\"版本\"],\"sentiment\":\"neutral\"}";

        service.applyModelOutput(digest, raw);

        assertEquals("讨论了版本更新", digest.getSummary());
        assertEquals("版本", digest.getTags());
        assertEquals("neutral", digest.getSentiment());
    }

    @Test
    @DisplayName("解析失败不抛异常：纯文本当作 summary，标签/情感留空")
    void shouldFallbackToPlainTextWhenNotJson() {
        GroupDigest digest = new GroupDigest();
        service.applyModelOutput(digest, "今天群里主要在聊新版本，气氛不错。");

        assertEquals("今天群里主要在聊新版本，气氛不错。", digest.getSummary());
        assertNull(digest.getTags());
        assertNull(digest.getSentiment());
    }

    @Test
    @DisplayName("JSON 不是对象（数组）时同样按纯文本兜底")
    void shouldFallbackWhenJsonIsNotObject() {
        GroupDigest digest = new GroupDigest();
        service.applyModelOutput(digest, "[\"a\",\"b\"]");

        assertEquals("[\"a\",\"b\"]", digest.getSummary());
        assertNull(digest.getTags());
    }

    @Test
    @DisplayName("提示词包含群号、日期与「只输出 JSON」要求")
    void shouldBuildJsonOnlyPrompt() {
        String prompt = service.buildPrompt("808521399", LocalDate.of(2026, 9, 16), "小明: 今天有人开黑吗");

        assertTrue(prompt.contains("808521399"));
        assertTrue(prompt.contains("2026-09-16"));
        assertTrue(prompt.contains("小明: 今天有人开黑吗"));
        assertTrue(prompt.contains("\"summary\""));
        assertTrue(prompt.contains("\"tags\""));
        assertTrue(prompt.contains("\"sentiment\""));
        assertTrue(prompt.contains("只输出一个 JSON 对象"));
    }

    @Test
    @DisplayName("toView 输出前端约定字段；无日报返回 null")
    void shouldBuildViewForFrontend() {
        GroupDigest digest = new GroupDigest();
        digest.setId(1L);
        digest.setGroupId("808521399");
        digest.setDigestDate(LocalDate.of(2026, 9, 16));
        digest.setSummary("今天主要在约开黑");
        digest.setTags("组队,开黑");
        digest.setSentiment("positive");
        digest.setMessageCount(128);

        var view = service.toView(digest);

        assertEquals("2026-09-16", view.get("digestDate"));
        assertEquals("今天主要在约开黑", view.get("summary"));
        assertEquals("组队,开黑", view.get("tags"));
        assertEquals("positive", view.get("sentiment"));
        assertEquals(128, view.get("messageCount"));
        assertNull(service.toView(null));
    }
}
