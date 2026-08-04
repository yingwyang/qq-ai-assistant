package com.qqai.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqai.entity.FileRecord;
import com.qqai.entity.Message;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RichMessageRenderer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static class RenderedMessage {
        private final String text;
        private final List<String> imageUrls;
        private final boolean hasValidContent;

        public RenderedMessage(String text, List<String> imageUrls, boolean hasValidContent) {
            this.text = text;
            this.imageUrls = imageUrls;
            this.hasValidContent = hasValidContent;
        }

        public String getText() { return text; }
        public List<String> getImageUrls() { return imageUrls; }
        public boolean isHasValidContent() { return hasValidContent; }
    }

    public static class RenderedBatch {
        private final String fullText;
        private final List<String> allImageUrls;
        private final boolean allHasValidContent;
        private final boolean wasTruncated;
        private final List<RenderedMessage> messages;

        public RenderedBatch(String fullText, List<String> allImageUrls, boolean allHasValidContent,
                             boolean wasTruncated, List<RenderedMessage> messages) {
            this.fullText = fullText;
            this.allImageUrls = allImageUrls;
            this.allHasValidContent = allHasValidContent;
            this.wasTruncated = wasTruncated;
            this.messages = messages;
        }

        public String getFullText() { return fullText; }
        public List<String> getAllImageUrls() { return allImageUrls; }
        public boolean isAllHasValidContent() { return allHasValidContent; }
        public boolean isWasTruncated() { return wasTruncated; }
        public List<RenderedMessage> getMessages() { return messages; }
    }

    public static RenderedBatch renderBatch(List<Message> messages,
                                            Map<String, FileRecord> fileRecordCache,
                                            String baseUrlForImages,
                                            int maxTotalChars) {
        List<RenderedMessage> renderedList = new ArrayList<>();
        List<String> allImageUrls = new ArrayList<>();
        StringBuilder fullTextBuilder = new StringBuilder();
        boolean allHasValidContent = false;
        boolean wasTruncated = false;
        int totalChars = 0;
        int keptCount = 0;

        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            RenderedMessage rendered = renderSingle(m, fileRecordCache, baseUrlForImages);

            int newLineChars = (i == 0) ? 0 : 1;
            int projectedTotal = totalChars + newLineChars + rendered.getText().length();

            if (projectedTotal > maxTotalChars && keptCount > 0) {
                wasTruncated = true;
                break;
            }

            if (i > 0) {
                fullTextBuilder.append("\n");
                totalChars += 1;
            }
            fullTextBuilder.append(rendered.getText());
            totalChars += rendered.getText().length();
            renderedList.add(rendered);
            allImageUrls.addAll(rendered.getImageUrls());
            if (rendered.isHasValidContent()) {
                allHasValidContent = true;
            }
            keptCount++;
        }

        if (messages.size() > keptCount) {
            wasTruncated = true;
        }

        String fullText = fullTextBuilder.toString();
        if (wasTruncated && keptCount > 0) {
            String truncNotice = "\n⚠️ 消息已截断，仅展示前 " + keptCount + " 条共 " + totalChars + " 字";
            fullText = fullText + truncNotice;
        }

        return new RenderedBatch(fullText, allImageUrls, allHasValidContent, wasTruncated, renderedList);
    }

    public static RenderedMessage renderSingle(Message m, Map<String, FileRecord> cache, String baseUrl) {
        List<String> imageUrls = new ArrayList<>();
        String nickname = resolveNickname(m);
        Message.MessageType type = m.getMessageType() != null ? m.getMessageType() : Message.MessageType.TEXT;
        StringBuilder textBuilder = new StringBuilder();
        boolean hasValidContent = computeHasValidContent(m, type);

        String content = m.getContent() != null ? m.getContent() : "";

        switch (type) {
            case TEXT -> renderText(textBuilder, nickname, content);
            case IMAGE -> renderImage(textBuilder, imageUrls, nickname, content, m, cache, baseUrl);
            case VIDEO -> renderVideo(textBuilder, imageUrls, nickname, content, m, cache);
            case VOICE -> renderVoice(textBuilder, nickname, content, m, cache);
            case AUDIO -> renderAudio(textBuilder, nickname, content, m, cache);
            case FILE -> renderFile(textBuilder, nickname, content, m, cache);
            case AT -> renderAt(textBuilder, nickname, content, m);
            case REPLY -> renderReply(textBuilder, imageUrls, nickname, content, m, cache, baseUrl);
            case FORWARD -> renderForward(textBuilder, nickname, content, m);
            case APP -> renderApp(textBuilder, nickname, content, m);
            default -> renderDefault(textBuilder, nickname, content, m, cache, type);
        }

        return new RenderedMessage(textBuilder.toString(), imageUrls, hasValidContent);
    }

    private static String resolveNickname(Message m) {
        if (m.getUserNickname() != null && !m.getUserNickname().isEmpty()) {
            return m.getUserNickname();
        }
        if (m.getUserQq() != null) {
            return m.getUserQq();
        }
        return "未知";
    }

    private static boolean computeHasValidContent(Message m, Message.MessageType type) {
        if (m.getContent() != null && !m.getContent().trim().isEmpty()) {
            return true;
        }
        if (m.getFileId() != null && !m.getFileId().isEmpty()) {
            return true;
        }
        if (m.getAtQq() != null && !m.getAtQq().isEmpty()) {
            return true;
        }
        if (m.getReplyToContent() != null && !m.getReplyToContent().isEmpty()) {
            return true;
        }
        if (m.getReplyToNickname() != null && !m.getReplyToNickname().isEmpty()) {
            return true;
        }
        if (m.getForwardContent() != null && !m.getForwardContent().isEmpty()) {
            return true;
        }
        if (m.getMiniAppContent() != null && !m.getMiniAppContent().isEmpty()) {
            return true;
        }
        return type == Message.MessageType.VOICE
                || type == Message.MessageType.AUDIO
                || type == Message.MessageType.VIDEO;
    }

    private static void renderText(StringBuilder sb, String nickname, String content) {
        sb.append(nickname).append(": ").append(content);
    }

    private static void renderImage(StringBuilder sb, List<String> imageUrls, String nickname,
                                    String content, Message m, Map<String, FileRecord> cache, String baseUrl) {
        sb.append(nickname).append(": ");
        FileRecord fr = (cache != null && m.getFileId() != null) ? cache.get(m.getFileId()) : null;

        if (fr != null) {
            sb.append("[图片 ");
            if (fr.getFileName() != null) sb.append(fr.getFileName());
            if (fr.getWidth() != null && fr.getHeight() != null) {
                sb.append(" ").append(fr.getWidth()).append("×").append(fr.getHeight());
            }
            String sizeStr = formatFileSize(fr.getFileSize());
            if (!sizeStr.isEmpty()) {
                sb.append(" ").append(sizeStr);
            }
            sb.append("]");

            String resolvedUrl = resolveImageUrl(fr.getUrl(), fr.getThumbnailUrl(), baseUrl);
            if (resolvedUrl != null) {
                imageUrls.add(resolvedUrl);
            }
        } else {
            sb.append("[图片]");
            // file_id=NULL 时，content 可能存储的是图片路径（如 /images/images/xxx.jpg）
            // 把它也加入 imageUrls 供视觉模型使用
            if (content != null && !content.isEmpty()) {
                String resolvedContentUrl = resolveImageUrl(content, null, baseUrl);
                if (resolvedContentUrl != null) {
                    imageUrls.add(resolvedContentUrl);
                }
            }
        }

        if (content != null && !content.isEmpty()) {
            sb.append(" ").append(content);
        }
    }

    private static void renderVideo(StringBuilder sb, List<String> imageUrls, String nickname,
                                    String content, Message m, Map<String, FileRecord> cache) {
        sb.append(nickname).append(": ");
        FileRecord fr = (cache != null && m.getFileId() != null) ? cache.get(m.getFileId()) : null;

        sb.append("[视频");
        if (fr != null) {
            if (fr.getFileName() != null) sb.append(" ").append(fr.getFileName());
            if (fr.getWidth() != null && fr.getHeight() != null) {
                sb.append(" ").append(fr.getWidth()).append("×").append(fr.getHeight());
            }
            if (fr.getDuration() != null) {
                sb.append(" ").append(fr.getDuration()).append("秒");
            }
            String sizeStr = formatFileSize(fr.getFileSize());
            if (!sizeStr.isEmpty()) {
                sb.append(" ").append(sizeStr);
            }
            if (fr.getThumbnailUrl() != null && !fr.getThumbnailUrl().isEmpty()) {
                imageUrls.add(fr.getThumbnailUrl());
            }
        }
        sb.append("]");

        if (content != null && !content.isEmpty()) {
            sb.append(" ").append(content);
        }
    }

    private static void renderVoice(StringBuilder sb, String nickname, String content,
                                    Message m, Map<String, FileRecord> cache) {
        sb.append(nickname).append(": ");
        FileRecord fr = (cache != null && m.getFileId() != null) ? cache.get(m.getFileId()) : null;

        sb.append("[语音");
        if (fr != null && fr.getDuration() != null) {
            sb.append(" ").append(fr.getDuration()).append("秒");
        }
        sb.append("]");

        if (content != null && !content.isEmpty()) {
            sb.append(" ").append(content);
        }
    }

    private static void renderAudio(StringBuilder sb, String nickname, String content,
                                    Message m, Map<String, FileRecord> cache) {
        sb.append(nickname).append(": ");
        FileRecord fr = (cache != null && m.getFileId() != null) ? cache.get(m.getFileId()) : null;

        sb.append("[音频");
        if (fr != null) {
            if (fr.getFileName() != null) sb.append(" ").append(fr.getFileName());
            if (fr.getDuration() != null) {
                sb.append(" ").append(fr.getDuration()).append("秒");
            }
        }
        sb.append("]");

        if (content != null && !content.isEmpty()) {
            sb.append(" ").append(content);
        }
    }

    private static void renderFile(StringBuilder sb, String nickname, String content,
                                   Message m, Map<String, FileRecord> cache) {
        sb.append(nickname).append(": ");
        FileRecord fr = (cache != null && m.getFileId() != null) ? cache.get(m.getFileId()) : null;

        sb.append("[文件");
        if (fr != null) {
            if (fr.getFileName() != null) sb.append(" ").append(fr.getFileName());
            if (fr.getMimeType() != null && !fr.getMimeType().isEmpty()) {
                sb.append(" ").append(fr.getMimeType());
            }
        }
        sb.append("]");

        if (content != null && !content.isEmpty()) {
            sb.append(" ").append(content);
        }
    }

    private static void renderAt(StringBuilder sb, String nickname, String content, Message m) {
        sb.append(nickname).append(": @");
        if (m.getAtQq() != null && !m.getAtQq().isEmpty()) {
            sb.append(m.getAtQq());
        }
        if (content != null && !content.isEmpty()) {
            sb.append(" ").append(content);
        }
    }

    private static void renderReply(StringBuilder sb, List<String> imageUrls, String nickname,
                                    String content, Message m, Map<String, FileRecord> cache, String baseUrl) {
        sb.append(nickname).append(": 回复【");
        String replyNick = m.getReplyToNickname() != null ? m.getReplyToNickname() : "";
        String replyContent = m.getReplyToContent() != null ? m.getReplyToContent() : "";
        sb.append(replyNick).append(": ").append(truncate(replyContent, 120));
        sb.append("】");

        if (content != null && !content.isEmpty()) {
            sb.append(" ").append(content);
        }

        if (m.getFileId() != null && cache != null) {
            FileRecord fr = cache.get(m.getFileId());
            if (fr != null) {
                Message.MessageType effectiveType;
                if (fr.getFileType() != null) {
                    effectiveType = switch (fr.getFileType()) {
                        case IMAGE -> Message.MessageType.IMAGE;
                        case VIDEO -> Message.MessageType.VIDEO;
                        case AUDIO -> Message.MessageType.AUDIO;
                        default -> Message.MessageType.FILE;
                    };
                } else {
                    effectiveType = Message.MessageType.FILE;
                }
                sb.append(" ");
                switch (effectiveType) {
                    case IMAGE -> {
                        StringBuilder tmpSb = new StringBuilder();
                        List<String> tmpUrls = new ArrayList<>();
                        renderImagePlaceholderOnly(tmpSb, tmpUrls, fr, baseUrl);
                        sb.append(tmpSb);
                        imageUrls.addAll(tmpUrls);
                    }
                    case VIDEO -> {
                        StringBuilder tmpSb = new StringBuilder();
                        List<String> tmpUrls = new ArrayList<>();
                        renderVideoPlaceholderOnly(tmpSb, tmpUrls, fr);
                        sb.append(tmpSb);
                        imageUrls.addAll(tmpUrls);
                    }
                    case AUDIO -> {
                        StringBuilder tmpSb = new StringBuilder();
                        renderAudioPlaceholderOnly(tmpSb, fr);
                        sb.append(tmpSb);
                    }
                    default -> {
                        StringBuilder tmpSb = new StringBuilder();
                        renderFilePlaceholderOnly(tmpSb, fr);
                        sb.append(tmpSb);
                    }
                }
            }
        }
    }

    private static void renderImagePlaceholderOnly(StringBuilder sb, List<String> imageUrls,
                                                   FileRecord fr, String baseUrl) {
        sb.append("[图片 ");
        if (fr.getFileName() != null) sb.append(fr.getFileName());
        if (fr.getWidth() != null && fr.getHeight() != null) {
            sb.append(" ").append(fr.getWidth()).append("×").append(fr.getHeight());
        }
        String sizeStr = formatFileSize(fr.getFileSize());
        if (!sizeStr.isEmpty()) sb.append(" ").append(sizeStr);
        sb.append("]");
        String resolved = resolveImageUrl(fr.getUrl(), fr.getThumbnailUrl(), baseUrl);
        if (resolved != null) imageUrls.add(resolved);
    }

    private static void renderVideoPlaceholderOnly(StringBuilder sb, List<String> imageUrls, FileRecord fr) {
        sb.append("[视频");
        if (fr.getFileName() != null) sb.append(" ").append(fr.getFileName());
        if (fr.getWidth() != null && fr.getHeight() != null) {
            sb.append(" ").append(fr.getWidth()).append("×").append(fr.getHeight());
        }
        if (fr.getDuration() != null) sb.append(" ").append(fr.getDuration()).append("秒");
        String sizeStr = formatFileSize(fr.getFileSize());
        if (!sizeStr.isEmpty()) sb.append(" ").append(sizeStr);
        sb.append("]");
        if (fr.getThumbnailUrl() != null && !fr.getThumbnailUrl().isEmpty()) {
            imageUrls.add(fr.getThumbnailUrl());
        }
    }

    private static void renderAudioPlaceholderOnly(StringBuilder sb, FileRecord fr) {
        sb.append("[音频");
        if (fr.getFileName() != null) sb.append(" ").append(fr.getFileName());
        if (fr.getDuration() != null) sb.append(" ").append(fr.getDuration()).append("秒");
        sb.append("]");
    }

    private static void renderFilePlaceholderOnly(StringBuilder sb, FileRecord fr) {
        sb.append("[文件");
        if (fr.getFileName() != null) sb.append(" ").append(fr.getFileName());
        if (fr.getMimeType() != null && !fr.getMimeType().isEmpty()) {
            sb.append(" ").append(fr.getMimeType());
        }
        sb.append("]");
    }

    private static void renderForward(StringBuilder sb, String nickname, String content, Message m) {
        sb.append(nickname).append(": [合并转发消息");
        List<ForwardSub> subs = parseForwardSubmessages(m.getForwardMessages());
        int totalCount = subs != null ? subs.size() : 0;
        Object forwardObj = m.getForwardMessages();
        if (forwardObj instanceof JsonNode jsonNode && jsonNode.isArray()) {
            totalCount = jsonNode.size();
        } else if (forwardObj instanceof List<?> list) {
            totalCount = list.size();
        }
        sb.append(" 包含 ").append(totalCount).append(" 条子消息]");

        if (subs != null && !subs.isEmpty()) {
            int displayCount = Math.min(3, subs.size());
            for (int i = 0; i < displayCount; i++) {
                ForwardSub sub = subs.get(i);
                String subText = truncate(sub.text, 80);
                sb.append("\n→ ").append(sub.sender).append(": ").append(subText);
            }
            if (totalCount > 3) {
                sb.append("\n(省略 ").append(totalCount - 3).append(" 条)");
            }
        }
    }

    private static void renderApp(StringBuilder sb, String nickname, String content, Message m) {
        sb.append(nickname).append(": ");
        String miniAppField = parseMiniAppField(m.getMiniAppContent());
        if (miniAppField != null) {
            sb.append("[小程序 ").append(miniAppField).append("]");
        } else {
            sb.append("[小程序]");
        }
        if (content != null && !content.isEmpty()) {
            sb.append(" ").append(content);
        }
    }

    private static void renderDefault(StringBuilder sb, String nickname, String content,
                                      Message m, Map<String, FileRecord> cache, Message.MessageType type) {
        sb.append(nickname).append(": [").append(type != null ? type.name() : "UNKNOWN").append("]");
        if (content != null && !content.isEmpty()) {
            sb.append(" ").append(content);
        }
        if (m.getFileId() != null && cache != null) {
            FileRecord fr = cache.get(m.getFileId());
            if (fr != null) {
                sb.append("[文件");
                if (fr.getFileName() != null) sb.append(":").append(fr.getFileName());
                sb.append("]");
            }
        }
    }

    private static String formatFileSize(Long bytes) {
        if (bytes == null || bytes == 0) {
            return "";
        }
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            double kb = bytes / 1024.0;
            return Math.round(kb * 10.0) / 10.0 + "KB";
        }
        if (bytes < 1024 * 1024 * 1024) {
            double mb = bytes / (1024.0 * 1024.0);
            return Math.round(mb * 10.0) / 10.0 + "MB";
        }
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        return Math.round(gb * 10.0) / 10.0 + "GB";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "…";
    }

    private static String resolveImageUrl(String urlOrPath, String thumbnailUrl, String baseUrlForImages) {
        String candidate = (urlOrPath != null && !urlOrPath.isEmpty()) ? urlOrPath : thumbnailUrl;
        if (candidate == null || candidate.isEmpty()) {
            return null;
        }

        if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
            try {
                URI uri = URI.create(candidate);
                String host = uri.getHost();
                if (host == null) return null;
                if (isSafeHost(host)) {
                    return candidate;
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        }

        if (candidate.startsWith("/") && baseUrlForImages != null && !baseUrlForImages.isEmpty()) {
            String base = baseUrlForImages.endsWith("/")
                    ? baseUrlForImages.substring(0, baseUrlForImages.length() - 1)
                    : baseUrlForImages;
            return base + candidate;
        }

        return null;
    }

    private static boolean isSafeHost(String host) {
        if (host.equalsIgnoreCase("localhost")) return true;
        if (host.equals("127.0.0.1")) return true;
        if (host.startsWith("192.168.")) return true;
        if (host.startsWith("10.")) return true;
        if (host.matches("^172\\.(1[6-9]|2[0-9]|3[01])\\..*")) return true;
        return false;
    }

    private static class ForwardSub {
        String sender;
        String text;
        ForwardSub(String sender, String text) {
            this.sender = sender;
            this.text = text;
        }
    }

    private static List<ForwardSub> parseForwardSubmessages(Object forwardMessages) {
        List<ForwardSub> result = new ArrayList<>();
        if (forwardMessages == null) return result;

        try {
            JsonNode root;
            if (forwardMessages instanceof JsonNode node) {
                root = node;
            } else {
                root = OBJECT_MAPPER.valueToTree(forwardMessages);
            }

            if (!root.isArray()) {
                String str = truncate(root.toString(), 80);
                result.add(new ForwardSub("未知", str));
                return result;
            }

            int count = 0;
            for (JsonNode item : root) {
                if (count >= 3) break;
                String sender = extractField(item, "sender", "role", "nickname", "name");
                String text = extractField(item, "text", "content", "raw_content", "message", "msg");
                if (text == null || text.isEmpty()) {
                    text = truncate(item.toString(), 80);
                }
                result.add(new ForwardSub(sender != null ? sender : "未知", text));
                count++;
            }
        } catch (Exception e) {
            String str = truncate(forwardMessages.toString(), 80);
            result.add(new ForwardSub("未知", str));
        }
        return result;
    }

    private static String extractField(JsonNode node, String... fieldNames) {
        for (String name : fieldNames) {
            JsonNode val = node.get(name);
            if (val != null && !val.isNull()) {
                if (val.isTextual()) return val.asText();
                String s = val.toString();
                if (s != null && !s.isEmpty() && !s.equals("null")) return s;
            }
        }
        return null;
    }

    private static String parseMiniAppField(String miniAppContentJson) {
        if (miniAppContentJson == null || miniAppContentJson.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(miniAppContentJson);
            String title = findTextValue(root, "title");
            if (title != null) return title;
            String desc = findTextValue(root, "desc", "description");
            if (desc != null) return desc;
            String pagePath = findTextValue(root, "page_path", "pagePath");
            return pagePath;
        } catch (Exception e) {
            return null;
        }
    }

    private static String findTextValue(JsonNode root, String... keys) {
        if (root == null) return null;
        for (String key : keys) {
            JsonNode val = root.get(key);
            if (val != null && val.isTextual() && !val.asText().isEmpty()) {
                return val.asText();
            }
        }
        if (root.isObject()) {
            var iter = root.fields();
            while (iter.hasNext()) {
                var entry = iter.next();
                String found = findTextValue(entry.getValue(), keys);
                if (found != null) return found;
            }
        }
        if (root.isArray()) {
            for (JsonNode child : root) {
                String found = findTextValue(child, keys);
                if (found != null) return found;
            }
        }
        return null;
    }
}
