package com.qqai.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqai.dto.webhook.MediaTaskPayload;
import com.qqai.dto.webhook.MessageParseResult;
import com.qqai.entity.Message;
import com.qqai.util.CqCodeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class MessageParserService {

    private static final Logger log = LoggerFactory.getLogger(MessageParserService.class);

    @Autowired
    private CqCodeUtils cqCodeUtils;

    @Autowired
    private MediaDownloadService mediaDownloadService;

    @Autowired
    private NapCatService napCatService;

    @Autowired
    private MessageService messageService;

    /**
     * 完整解析（含同步媒体下载/转码）。保留向后兼容，新链路请用 {@link #parseLightweight}。
     */
    public MessageParseResult parse(ObjectNode json) {
        MessageParseResult result = new MessageParseResult();
        extractBasicFields(json, result);

        if (result.getRawMessage() != null && result.getRawMessage().contains("[CQ:")) {
            log.info("解析 CQ 码 - rawMessage长度={}, 包含CQ码", result.getRawMessage().length());
            if (result.getRawMessage().contains("[CQ:json")) {
                log.info("检测到 [CQ:json] 类型消息");
            }
            parseCqCodes(result, json);
        } else {
            log.info("未检测到 CQ 码 - rawMessage={}", result.getRawMessage());
        }

        return result;
    }

    /**
     * 轻量解析：仅提取字段 + 识别消息类型 + 收集媒体任务，不执行任何 HTTP 下载或子进程转码。
     * 媒体任务以 {@link MediaTaskPayload} 形式收集到 {@link MessageParseResult#getMediaTasks()}，
     * 由 RootWebhookController 入库后投递到 RabbitMQ 异步处理。
     *
     * 注意：合并转发 [CQ:forward] 仍同步拉取（涉及 NapCat API，逻辑复杂，暂不异步化）。
     */
    public MessageParseResult parseLightweight(ObjectNode json) {
        MessageParseResult result = new MessageParseResult();
        extractBasicFields(json, result);

        if (result.getRawMessage() != null && result.getRawMessage().contains("[CQ:")) {
            log.info("轻量解析 CQ 码 - rawMessage长度={}, 包含CQ码", result.getRawMessage().length());
            parseCqCodesLightweight(result, json);
        } else {
            log.info("未检测到 CQ 码 - rawMessage={}", result.getRawMessage());
        }

        return result;
    }

    /**
     * 提取基本字段（标准 OneBot 11 + NapCat fallback + elements 数组 + message 数组小程序解析）。
     * 不含任何 IO 操作，parse 与 parseLightweight 共用。
     */
    private void extractBasicFields(ObjectNode json, MessageParseResult result) {
        // 尝试标准 OneBot 11 格式
        result.setGroupId(json.has("group_id") ? json.get("group_id").asLong() : null);
        result.setGroupName(json.has("group_name") ? json.get("group_name").asText() : null);
        ObjectNode sender = (ObjectNode) json.get("sender");
        if (sender != null) {
            result.setUserId(sender.has("user_id") ? sender.get("user_id").asLong() : null);
            result.setNickname(sender.has("nickname") ? sender.get("nickname").asText() : null);
        }
        result.setRawMessage(json.has("raw_message") ? json.get("raw_message").asText() : null);
        result.setMessageId(json.has("message_id") ? json.get("message_id").asInt() : null);
        result.setRawMsgTime(json.has("time") ? json.get("time").asLong() : null);
        result.setMsgSeq(json.has("message_seq") ? json.get("message_seq").asInt() : null);

        // 如果标准格式没有，尝试 NapCat 格式
        if (result.getGroupId() == null) {
            result.setGroupId(json.has("peerUin") ? json.get("peerUin").asLong() : null);
        }
        if (result.getGroupName() == null) {
            result.setGroupName(json.has("peerName") ? json.get("peerName").asText() : null);
        }
        if (result.getUserId() == null) {
            result.setUserId(json.has("senderUin") ? json.get("senderUin").asLong() : null);
        }
        if (result.getNickname() == null) {
            result.setNickname(json.has("sendNickName") ? json.get("sendNickName").asText() : null);
        }
        if (result.getMessageId() == null) {
            result.setMessageId(json.has("msgId") ? json.get("msgId").asInt() : null);
        }

        // 检查 raw_message 是否包含 CQ 码（NapCat 已经格式化好的消息）
        boolean hasRawMessage = result.getRawMessage() != null && !result.getRawMessage().isEmpty();
        boolean isImageFromRaw = hasRawMessage && result.getRawMessage().contains("[CQ:image");

        // 从 NapCat elements 数组中提取消息内容和类型
        // 但对于图片消息，优先使用 raw_message 中的完整 CQ 码
        if (!isImageFromRaw) {
            log.info("尝试从 elements 数组解析消息");
            parseElements(json, result);
        }

        if (json.has("message")) {
            Object messageField = json.get("message");
            log.info("检测到 message 字段, type={}", messageField.getClass().getSimpleName());
            if (messageField instanceof ArrayNode) {
                ArrayNode messageArray = (ArrayNode) messageField;
                for (int i = 0; i < messageArray.size(); i++) {
                    ObjectNode item = (ObjectNode) messageArray.get(i);
                    String type = item.has("type") ? item.get("type").asText() : null;
                    if ("json".equals(type)) {
                        ObjectNode data = (ObjectNode) item.get("data");
                        String content = data.has("content") ? data.get("content").asText() : null;
                        if (content != null && !content.isEmpty()) {
                            // 反转义 HTML 实体，确保存储的是有效的 JSON 字符串
                            String decoded = content
                                    .replace("&amp;", "&")
                                    .replace("&#91;", "[")
                                    .replace("&#93;", "]")
                                    .replace("&#44;", ",")
                                    .replace("&#34;", "\"")
                                    .replace("&#39;", "'")
                                    .replace("&lt;", "<")
                                    .replace("&gt;", ">")
                                    .replace("&quot;", "\"");
                            result.setMsgType(Message.MessageType.APP);
                            result.setMiniAppContent(decoded);
                            result.setRawMessage("[CQ:json]");
                            log.info("从 message 数组解析小程序分享消息, decoded长度={}", decoded.length());
                        }
                    }
                }
            }
        }
    }

    private void parseElements(ObjectNode json, MessageParseResult result) {
        ArrayNode elements = (ArrayNode) json.get("elements");
        if (elements == null || elements.isEmpty()) {
            return;
        }
        StringBuilder msgBuilder = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            ObjectNode element = (ObjectNode) elements.get(i);
            if (element == null) continue;

            Integer elementType = element.has("elementType") ? element.get("elementType").asInt() : null;
            if (elementType == null) continue;

            switch (elementType) {
                case 1: // 文本消息
                    ObjectNode textElement = (ObjectNode) element.get("textElement");
                    if (textElement != null) {
                        String content = textElement.has("content") ? textElement.get("content").asText() : null;
                        if (content != null) {
                            msgBuilder.append(content);
                        }
                    }
                    break;
                case 2: // 图片消息
                    ObjectNode picElement = (ObjectNode) element.get("picElement");
                    if (picElement != null) {
                        String picUrl = picElement.has("sourcePath") ? picElement.get("sourcePath").asText() : null;
                        if (picUrl == null || picUrl.isEmpty()) {
                            picUrl = picElement.has("thumbPath") ? picElement.get("thumbPath").asText() : null;
                        }
                        if (picUrl != null && !picUrl.isEmpty()) {
                            msgBuilder.append("[CQ:image,file=").append(picElement.has("fileName") ? picElement.get("fileName").asText() : "").append(",url=").append(picUrl).append("]");
                            result.setMsgType(Message.MessageType.IMAGE);
                        }
                    }
                    break;
                case 3: // 语音消息
                    ObjectNode pttElement = (ObjectNode) element.get("pttElement");
                    if (pttElement != null) {
                        String voiceUrl = pttElement.has("filePath") ? pttElement.get("filePath").asText() : null;
                        if (voiceUrl == null || voiceUrl.isEmpty()) {
                            voiceUrl = pttElement.has("fileName") ? pttElement.get("fileName").asText() : null;
                        }
                        if (voiceUrl != null && !voiceUrl.isEmpty()) {
                            String fileName = pttElement.has("fileName") ? pttElement.get("fileName").asText() : null;
                            if (fileName == null) fileName = "voice.silk";
                            msgBuilder.append("[CQ:record,file=").append(fileName).append(",url=").append(voiceUrl).append("]");
                            result.setMsgType(Message.MessageType.VOICE);
                        }
                    }
                    break;
                case 4: // 视频消息
                    ObjectNode videoElement = (ObjectNode) element.get("videoElement");
                    if (videoElement != null) {
                        String videoUrl = videoElement.has("filePath") ? videoElement.get("filePath").asText() : null;
                        if (videoUrl == null || videoUrl.isEmpty()) {
                            videoUrl = videoElement.has("fileName") ? videoElement.get("fileName").asText() : null;
                        }
                        if (videoUrl != null && !videoUrl.isEmpty()) {
                            String fileName = videoElement.has("fileName") ? videoElement.get("fileName").asText() : null;
                            if (fileName == null) fileName = "video.mp4";
                            msgBuilder.append("[CQ:video,file=").append(fileName).append(",url=").append(videoUrl).append("]");
                            result.setMsgType(Message.MessageType.VIDEO);
                        }
                    }
                    break;
                case 6: // 文件消息
                    result.setMsgType(Message.MessageType.FILE);
                    break;
                case 10: // JSON消息（小程序分享）
                    ObjectNode jsonElement = (ObjectNode) element.get("jsonElement");
                    if (jsonElement != null) {
                        String jsonContent = jsonElement.has("content") ? jsonElement.get("content").asText() : null;
                        if (jsonContent != null && !jsonContent.isEmpty()) {
                            // 反转义 HTML 实体
                            String decoded = jsonContent
                                    .replace("&amp;", "&")
                                    .replace("&#91;", "[")
                                    .replace("&#93;", "]")
                                    .replace("&#44;", ",")
                                    .replace("&#34;", "\"")
                                    .replace("&#39;", "'")
                                    .replace("&lt;", "<")
                                    .replace("&gt;", ">")
                                    .replace("&quot;", "\"");
                            result.setMsgType(Message.MessageType.APP);
                            result.setMiniAppContent(decoded);
                            msgBuilder.append("[CQ:json]");
                            log.info("解析小程序分享消息(elements), decoded长度={}", decoded.length());
                        }
                    }
                    break;
            }
        }
        if (msgBuilder.length() > 0) {
            result.setRawMessage(msgBuilder.toString());
        }
    }

    /**
     * 原始 CQ 码解析（含同步媒体下载/转码）。保留供 {@link #parse} 使用。
     */
    private void parseCqCodes(MessageParseResult result, ObjectNode json) {
        String rawMessage = result.getRawMessage();
        String groupId = String.valueOf(result.getGroupId());

        if (rawMessage.contains("[CQ:image")) {
            result.setMsgType(Message.MessageType.IMAGE);
            String localUrl = mediaDownloadService.downloadMediaToLocal(rawMessage, groupId, "images", ".jpg");
            if (localUrl != null) {
                result.setRawMessage(localUrl);
            }
        } else if (rawMessage.contains("[CQ:record") || rawMessage.contains("[CQ:voice")) {
            result.setMsgType(Message.MessageType.VOICE);
            String localUrl = mediaDownloadService.downloadMediaToLocal(rawMessage, groupId, "voice", ".amr");
            if (localUrl != null) {
                result.setRawMessage(localUrl);
            }
        } else if (rawMessage.contains("[CQ:video")) {
            result.setMsgType(Message.MessageType.VIDEO);
            String localUrl = mediaDownloadService.downloadMediaToLocal(rawMessage, groupId, "video", ".mp4");
            if (localUrl != null) {
                result.setRawMessage(localUrl);
            }
        } else if (rawMessage.contains("[CQ:file")) {
            result.setMsgType(Message.MessageType.FILE);
        } else if (rawMessage.contains("[CQ:forward")) {
            handleForward(result, json, rawMessage, groupId);
        } else if (rawMessage.contains("[CQ:reply")) {
            handleReply(result, rawMessage);
        } else if (rawMessage.contains("[CQ:json")) {
            handleJson(result, rawMessage);
        }
    }

    /**
     * 轻量 CQ 码解析：识别类型 + 收集媒体任务（不下载）。
     * 图片/语音/视频 → 收集到 mediaTasks；合并转发/回复/JSON → 同步处理（轻量或保留原逻辑）。
     */
    private void parseCqCodesLightweight(MessageParseResult result, ObjectNode json) {
        String rawMessage = result.getRawMessage();
        String groupId = String.valueOf(result.getGroupId());

        if (rawMessage.contains("[CQ:image")) {
            result.setMsgType(Message.MessageType.IMAGE);
            addMediaTask(result, rawMessage, groupId, "images", ".jpg");
        } else if (rawMessage.contains("[CQ:record") || rawMessage.contains("[CQ:voice")) {
            result.setMsgType(Message.MessageType.VOICE);
            addMediaTask(result, rawMessage, groupId, "voice", ".amr");
        } else if (rawMessage.contains("[CQ:video")) {
            result.setMsgType(Message.MessageType.VIDEO);
            addMediaTask(result, rawMessage, groupId, "video", ".mp4");
        } else if (rawMessage.contains("[CQ:file")) {
            result.setMsgType(Message.MessageType.FILE);
        } else if (rawMessage.contains("[CQ:forward")) {
            // 合并转发仍同步拉取（涉及 NapCat API，逻辑复杂，暂不异步化）
            handleForward(result, json, rawMessage, groupId);
        } else if (rawMessage.contains("[CQ:reply")) {
            handleReply(result, rawMessage);
        } else if (rawMessage.contains("[CQ:json")) {
            handleJson(result, rawMessage);
        }
    }

    /** 收集媒体任务到 result.mediaTasks（不执行下载） */
    private void addMediaTask(MessageParseResult result, String rawMessage, String groupId,
                              String mediaType, String extension) {
        String url = mediaDownloadService.extractUrlFromCQ(rawMessage);
        result.getMediaTasks().add(new MediaTaskPayload(rawMessage, url, groupId, mediaType, extension));
        log.info("收集媒体任务: type={}, ext={}, groupId={}, url={}", mediaType, extension, groupId, url);
    }

    private void handleForward(MessageParseResult result, ObjectNode json, String rawMessage, String groupId) {
        result.setMsgType(Message.MessageType.FORWARD);
        ArrayNode parsedForwardMessages = napCatService.extractForwardMessagesFromPayload(json);
        if (parsedForwardMessages != null && !parsedForwardMessages.isEmpty()) {
            napCatService.downloadForwardMediaToLocal(parsedForwardMessages, groupId);
            result.setForwardContent(parsedForwardMessages.toString());
            log.info("从 message 数组解析到合并转发消息详情, 共 {} 条子消息", parsedForwardMessages.size());
        } else {
            String forwardId = cqCodeUtils.extractForwardId(rawMessage);
            if (forwardId != null && !forwardId.isEmpty()) {
                try {
                    ArrayNode forwardMessages = napCatService.getForwardMsg(forwardId);
                    if (forwardMessages != null && !forwardMessages.isEmpty()) {
                        napCatService.downloadForwardMediaToLocal(forwardMessages, groupId);
                        result.setForwardContent(forwardMessages.toString());
                        log.info("合并转发消息详情已拉取, forwardId={}, 共 {} 条子消息", forwardId, forwardMessages.size());
                    }
                } catch (Exception e) {
                    log.error("拉取合并转发消息详情失败, forwardId={}: {}", forwardId, e.getMessage());
                }
            }
        }
    }

    private void handleReply(MessageParseResult result, String rawMessage) {
        result.setMsgType(Message.MessageType.REPLY);
        Long replyQqMessageId = cqCodeUtils.extractReplyMessageId(rawMessage);
        if (replyQqMessageId != null) {
            Optional<Message> replied = messageService.findByMessageId(String.valueOf(replyQqMessageId));
            if (replied.isPresent()) {
                Message target = replied.get();
                result.setReplyToMessageId(target.getId());
                result.setReplyToNickname(target.getUserNickname());
                result.setReplyToContent(buildReplyToContent(target));
            }
        }
    }

    private void handleJson(MessageParseResult result, String rawMessage) {
        result.setMsgType(Message.MessageType.APP);
        String jsonData = cqCodeUtils.extractJsonData(rawMessage);
        if (jsonData != null && !jsonData.isEmpty()) {
            result.setMiniAppContent(jsonData);
            log.info("解析小程序分享消息, jsonData长度={}", jsonData.length());
        } else if (result.getMiniAppContent() == null || result.getMiniAppContent().isEmpty()) {
            log.info("CQ:json 解析为空，但未从 message 数组获取到小程序数据");
        }
    }

    private String buildReplyToContent(Message target) {
        if (target == null) {
            return null;
        }
        String content = target.getContent();
        if (content != null) {
            content = content.replaceAll("\\[CQ:reply[^\\]]*\\]", "").trim();
            if (!content.isEmpty()) {
                return content;
            }
        }
        String innerReplyContent = target.getReplyToContent();
        if (innerReplyContent != null) {
            innerReplyContent = innerReplyContent.replaceAll("\\[CQ:reply[^\\]]*\\]", "").trim();
            if (!innerReplyContent.isEmpty()) {
                return innerReplyContent;
            }
        }
        return null;
    }
}
