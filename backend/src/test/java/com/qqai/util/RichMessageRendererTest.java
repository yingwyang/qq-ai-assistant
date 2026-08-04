package com.qqai.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqai.entity.FileRecord;
import com.qqai.entity.Message;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RichMessageRendererTest {

    private Message baseTextMessage(String nickname, String qq, String content) {
        Message m = new Message();
        m.setUserNickname(nickname);
        m.setUserQq(qq);
        m.setGroupId("10000");
        m.setMessageType(Message.MessageType.TEXT);
        m.setContent(content);
        m.setSendTime(java.time.LocalDateTime.now());
        return m;
    }

    private Message baseMessageWithFile(String nickname, String qq, Message.MessageType type,
                                        String fileId, String content) {
        Message m = new Message();
        m.setUserNickname(nickname);
        m.setUserQq(qq);
        m.setGroupId("10000");
        m.setMessageType(type);
        m.setFileId(fileId);
        m.setContent(content);
        m.setSendTime(java.time.LocalDateTime.now());
        return m;
    }

    @Test
    void testTextWithContent() {
        Message m = baseTextMessage("小明", "12345", "今天天气不错");
        RichMessageRenderer.RenderedMessage r = RichMessageRenderer.renderSingle(m, null, null);
        assertEquals("小明: 今天天气不错", r.getText());
        assertTrue(r.isHasValidContent());
        assertTrue(r.getImageUrls().isEmpty());
    }

    @Test
    void testTextEmptyContent() {
        Message m = baseTextMessage("小明", "12345", "   ");
        RichMessageRenderer.RenderedMessage r = RichMessageRenderer.renderSingle(m, null, null);
        assertEquals("小明:    ", r.getText());
        assertFalse(r.isHasValidContent());
    }

    @Test
    void testImageWithFullFileRecord() {
        Message m = baseMessageWithFile("小红", "54321", Message.MessageType.IMAGE, "f_img_001", "看这张图");

        FileRecord fr = new FileRecord();
        fr.setFileId("f_img_001");
        fr.setFileName("cat.jpg");
        fr.setWidth(1920);
        fr.setHeight(1080);
        fr.setFileSize(1234567L);
        fr.setUrl("/uploads/2024/cat.jpg");
        fr.setThumbnailUrl("/uploads/thumbs/cat.jpg");

        Map<String, FileRecord> cache = Map.of("f_img_001", fr);
        RichMessageRenderer.RenderedMessage r = RichMessageRenderer.renderSingle(m, cache, "http://localhost:8081");

        assertTrue(r.getText().contains("[图片"));
        assertTrue(r.getText().contains("cat.jpg"));
        assertTrue(r.getText().contains("1920×1080"));
        assertTrue(r.getText().contains("看这张图"));
        assertTrue(r.isHasValidContent());
        assertFalse(r.getImageUrls().isEmpty());
        assertEquals("http://localhost:8081/uploads/2024/cat.jpg", r.getImageUrls().get(0));
    }

    @Test
    void testImageWithoutFileRecord() {
        Message m = baseMessageWithFile("小红", "54321", Message.MessageType.IMAGE, "f_missing", null);
        RichMessageRenderer.RenderedMessage r = RichMessageRenderer.renderSingle(m, Map.of(), null);
        assertTrue(r.getText().contains("[图片]"));
        assertFalse(r.getText().contains("×"));
        assertTrue(r.isHasValidContent());
        assertTrue(r.getImageUrls().isEmpty());
    }

    @Test
    void testVideo() {
        Message m = baseMessageWithFile("小李", "111", Message.MessageType.VIDEO, "f_vid_001", "好玩的视频");
        FileRecord fr = new FileRecord();
        fr.setFileId("f_vid_001");
        fr.setFileName("demo.mp4");
        fr.setWidth(1280);
        fr.setHeight(720);
        fr.setDuration(45);
        fr.setFileSize(20971520L);
        fr.setThumbnailUrl("/uploads/thumbs/demo.jpg");
        Map<String, FileRecord> cache = Map.of("f_vid_001", fr);
        RichMessageRenderer.RenderedMessage r = RichMessageRenderer.renderSingle(m, cache, null);
        assertTrue(r.getText().contains("[视频"));
        assertTrue(r.getText().contains("demo.mp4"));
        assertTrue(r.getText().contains("1280×720"));
        assertTrue(r.getText().contains("45秒"));
        assertFalse(r.getImageUrls().isEmpty());
    }

    @Test
    void testVoice() {
        Message m = baseMessageWithFile("小王", "222", Message.MessageType.VOICE, "f_voice_001", null);
        FileRecord fr = new FileRecord();
        fr.setFileId("f_voice_001");
        fr.setDuration(8);
        Map<String, FileRecord> cache = Map.of("f_voice_001", fr);
        RichMessageRenderer.RenderedMessage r = RichMessageRenderer.renderSingle(m, cache, null);
        assertTrue(r.getText().contains("[语音"));
        assertTrue(r.getText().contains("8秒"));
        assertTrue(r.isHasValidContent());
    }

    @Test
    void testAudio() {
        Message m = baseMessageWithFile("小赵", "333", Message.MessageType.AUDIO, "f_audio_001", "听歌");
        FileRecord fr = new FileRecord();
        fr.setFileId("f_audio_001");
        fr.setFileName("song.mp3");
        fr.setDuration(245);
        Map<String, FileRecord> cache = Map.of("f_audio_001", fr);
        RichMessageRenderer.RenderedMessage r = RichMessageRenderer.renderSingle(m, cache, null);
        assertTrue(r.getText().contains("[音频"));
        assertTrue(r.getText().contains("song.mp3"));
        assertTrue(r.getText().contains("245秒"));
        assertTrue(r.getText().contains("听歌"));
    }

    @Test
    void testFile() {
        Message m = baseMessageWithFile("小孙", "444", Message.MessageType.FILE, "f_file_001", "文档");
        FileRecord fr = new FileRecord();
        fr.setFileId("f_file_001");
        fr.setFileName("report.pdf");
        fr.setMimeType("application/pdf");
        Map<String, FileRecord> cache = Map.of("f_file_001", fr);
        RichMessageRenderer.RenderedMessage r = RichMessageRenderer.renderSingle(m, cache, null);
        assertTrue(r.getText().contains("[文件"));
        assertTrue(r.getText().contains("report.pdf"));
        assertTrue(r.getText().contains("application/pdf"));
    }

    @Test
    void testAtMessage() {
        Message m = new Message();
        m.setUserNickname("管理员");
        m.setUserQq("999");
        m.setGroupId("10000");
        m.setMessageType(Message.MessageType.AT);
        m.setAtQq("12345");
        m.setContent("请注意");
        m.setSendTime(java.time.LocalDateTime.now());
        RichMessageRenderer.RenderedMessage r = RichMessageRenderer.renderSingle(m, null, null);
        assertTrue(r.getText().contains("@12345"));
        assertTrue(r.getText().contains("请注意"));
        assertTrue(r.isHasValidContent());
    }

    @Test
    void testReplyLongContentTruncated() {
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longContent.append("a");
        }
        Message m = new Message();
        m.setUserNickname("回复者");
        m.setUserQq("888");
        m.setGroupId("10000");
        m.setMessageType(Message.MessageType.REPLY);
        m.setReplyToNickname("原作者");
        m.setReplyToContent(longContent.toString());
        m.setContent("我的回复");
        m.setSendTime(java.time.LocalDateTime.now());
        RichMessageRenderer.RenderedMessage r = RichMessageRenderer.renderSingle(m, null, null);
        assertTrue(r.getText().contains("回复【原作者:"));
        assertTrue(r.getText().contains("…"));
        assertTrue(r.getText().contains("我的回复"));
        String replyPart = r.getText().substring(r.getText().indexOf("回复【") + 4, r.getText().indexOf("】"));
        int colonIdx = replyPart.indexOf(":");
        String replyContentPart = colonIdx >= 0 ? replyPart.substring(colonIdx + 1) : replyPart;
        while (replyContentPart.startsWith(" ")) replyContentPart = replyContentPart.substring(1);
        assertEquals(120, replyContentPart.length());
        assertEquals("…", replyContentPart.substring(119));
        assertTrue(r.isHasValidContent());
    }

    @Test
    void testForward10Submessages() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, String>> subs = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("sender", "用户" + i);
            item.put("content", "这是第" + i + "条消息的内容内容内容内容内容");
            subs.add(item);
        }
        String forwardJson = mapper.writeValueAsString(subs);

        Message m = new Message();
        m.setUserNickname("转发者");
        m.setUserQq("777");
        m.setGroupId("10000");
        m.setMessageType(Message.MessageType.FORWARD);
        m.setForwardContent(forwardJson);
        m.setSendTime(java.time.LocalDateTime.now());

        RichMessageRenderer.RenderedMessage r = RichMessageRenderer.renderSingle(m, null, null);
        assertTrue(r.getText().contains("合并转发消息"));
        assertTrue(r.getText().contains("包含 10 条子消息"));
        assertTrue(r.getText().contains("用户1:"));
        assertTrue(r.getText().contains("用户2:"));
        assertTrue(r.getText().contains("用户3:"));
        assertFalse(r.getText().contains("用户4:"));
        assertTrue(r.getText().contains("省略 7 条"));
        assertTrue(r.isHasValidContent());
    }

    @Test
    void testAppWithMiniAppTitle() {
        String miniAppJson = "{\"title\":\"我的小程序\",\"desc\":\"一个好用的工具\",\"page_path\":\"pages/index/index\"}";
        Message m = new Message();
        m.setUserNickname("分享者");
        m.setUserQq("666");
        m.setGroupId("10000");
        m.setMessageType(Message.MessageType.APP);
        m.setMiniAppContent(miniAppJson);
        m.setContent("推荐给大家");
        m.setSendTime(java.time.LocalDateTime.now());
        RichMessageRenderer.RenderedMessage r = RichMessageRenderer.renderSingle(m, null, null);
        assertTrue(r.getText().contains("[小程序 我的小程序]"));
        assertTrue(r.getText().contains("推荐给大家"));
        assertTrue(r.isHasValidContent());
    }

    @Test
    void testCompletelyEmptyMessage() {
        Message m = new Message();
        m.setUserNickname("无名氏");
        m.setUserQq("000");
        m.setGroupId("10000");
        m.setMessageType(Message.MessageType.TEXT);
        m.setContent("");
        m.setSendTime(java.time.LocalDateTime.now());
        RichMessageRenderer.RenderedMessage r = RichMessageRenderer.renderSingle(m, null, null);
        assertEquals("无名氏: ", r.getText());
        assertFalse(r.isHasValidContent());
    }

    @Test
    void testFormatFileSize() {
        RichMessageRenderer.RenderedMessage r;
        Message m;
        FileRecord fr;
        Map<String, FileRecord> cache;

        m = baseMessageWithFile("t", "1", Message.MessageType.IMAGE, "f1", null);
        fr = new FileRecord();
        fr.setFileId("f1");
        fr.setFileName("x.jpg");
        fr.setFileSize(1234L);
        cache = Map.of("f1", fr);
        r = RichMessageRenderer.renderSingle(m, cache, null);
        assertTrue(r.getText().contains("1.2KB"), "1234B expected 1.2KB, actual: " + r.getText());

        m = baseMessageWithFile("t", "1", Message.MessageType.IMAGE, "f2", null);
        fr = new FileRecord();
        fr.setFileId("f2");
        fr.setFileName("x.jpg");
        fr.setFileSize(2048L);
        cache = Map.of("f2", fr);
        r = RichMessageRenderer.renderSingle(m, cache, null);
        assertTrue(r.getText().contains("2.0KB"), "2048B expected 2.0KB, actual: " + r.getText());

        m = baseMessageWithFile("t", "1", Message.MessageType.IMAGE, "f3", null);
        fr = new FileRecord();
        fr.setFileId("f3");
        fr.setFileName("x.jpg");
        fr.setFileSize(1234567L);
        cache = Map.of("f3", fr);
        r = RichMessageRenderer.renderSingle(m, cache, null);
        assertTrue(r.getText().contains("1.2MB"), "1234567B expected 1.2MB, actual: " + r.getText());

        long hundredMb = 1024L * 1024L * 100L;
        m = baseMessageWithFile("t", "1", Message.MessageType.IMAGE, "f4", null);
        fr = new FileRecord();
        fr.setFileId("f4");
        fr.setFileName("x.jpg");
        fr.setFileSize(hundredMb);
        cache = Map.of("f4", fr);
        r = RichMessageRenderer.renderSingle(m, cache, null);
        assertTrue(r.getText().contains("100.0MB"), "100MB expected 100.0MB, actual: " + r.getText());

        m = baseMessageWithFile("t", "1", Message.MessageType.IMAGE, "f5", null);
        fr = new FileRecord();
        fr.setFileId("f5");
        fr.setFileName("x.jpg");
        fr.setFileSize(null);
        cache = Map.of("f5", fr);
        r = RichMessageRenderer.renderSingle(m, cache, null);
        assertFalse(r.getText().matches(".*\\d+\\.*\\d*[KMG]?B.*"), "null should skip size, actual: " + r.getText());

        m = baseMessageWithFile("t", "1", Message.MessageType.IMAGE, "f6", null);
        fr = new FileRecord();
        fr.setFileId("f6");
        fr.setFileName("x.jpg");
        fr.setFileSize(0L);
        cache = Map.of("f6", fr);
        r = RichMessageRenderer.renderSingle(m, cache, null);
        assertFalse(r.getText().matches(".*\\d+\\.*\\d*[KMG]?B.*"), "0 should skip size, actual: " + r.getText());
    }

    @Test
    void testResolveImageUrl() {
        Message m1 = baseMessageWithFile("t", "1", Message.MessageType.IMAGE, "f_rel", null);
        FileRecord fr1 = new FileRecord();
        fr1.setFileId("f_rel");
        fr1.setFileName("a.jpg");
        fr1.setUrl("/uploads/a.jpg");
        Map<String, FileRecord> cache1 = Map.of("f_rel", fr1);
        RichMessageRenderer.RenderedMessage r1 = RichMessageRenderer.renderSingle(m1, cache1, "http://localhost:8081");
        assertEquals(1, r1.getImageUrls().size());
        assertEquals("http://localhost:8081/uploads/a.jpg", r1.getImageUrls().get(0));

        Message m2 = baseMessageWithFile("t", "1", Message.MessageType.IMAGE, "f_evil", null);
        FileRecord fr2 = new FileRecord();
        fr2.setFileId("f_evil");
        fr2.setFileName("x.jpg");
        fr2.setUrl("http://evil.com/x.jpg");
        Map<String, FileRecord> cache2 = Map.of("f_evil", fr2);
        RichMessageRenderer.RenderedMessage r2 = RichMessageRenderer.renderSingle(m2, cache2, "http://localhost:8081");
        assertTrue(r2.getImageUrls().isEmpty(), "SSRF guard: evil URL should be excluded from imageUrls");
        assertTrue(r2.getText().contains("[图片"));
    }

    @Test
    void testBatchTruncation() {
        List<Message> messages = new ArrayList<>();
        StringBuilder perMsgContent = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            perMsgContent.append("x");
        }
        for (int i = 0; i < 100; i++) {
            Message m = baseTextMessage("用户" + i, String.valueOf(10000 + i), perMsgContent.toString());
            messages.add(m);
        }

        RichMessageRenderer.RenderedBatch batch = RichMessageRenderer.renderBatch(
                messages, Map.of(), null, 500);

        assertTrue(batch.isWasTruncated(), "expected wasTruncated=true");
        assertTrue(batch.getFullText().contains("消息已截断"), "expected truncation notice");
        assertTrue(batch.isAllHasValidContent());
        assertFalse(batch.getMessages().isEmpty());
        assertTrue(batch.getMessages().size() < 100,
                "expected truncated: kept " + batch.getMessages().size() + " of 100");
        assertTrue(batch.getFullText().contains("仅展示前 " + batch.getMessages().size() + " 条"),
                "truncation notice should reference correct kept count");
    }
}
