package com.qqai.dto.webhook;

import com.qqai.entity.Message;

import java.util.ArrayList;
import java.util.List;

public class MessageParseResult {
    private Long groupId;
    private String groupName;
    private Long userId;
    private String nickname;
    private String rawMessage;
    private Integer messageId;
    private Message.MessageType msgType = Message.MessageType.TEXT;
    private Long rawMsgTime;
    private Integer msgSeq;
    private Long replyToMessageId;
    private String replyToNickname;
    private String replyToContent;
    private String forwardContent;
    private String miniAppContent;

    /** 轻量解析阶段收集的媒体任务（图片/语音/视频），由 Controller 入库后投递到 MQ */
    private List<MediaTaskPayload> mediaTasks = new ArrayList<>();

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getRawMessage() {
        return rawMessage;
    }

    public void setRawMessage(String rawMessage) {
        this.rawMessage = rawMessage;
    }

    public Integer getMessageId() {
        return messageId;
    }

    public void setMessageId(Integer messageId) {
        this.messageId = messageId;
    }

    public Message.MessageType getMsgType() {
        return msgType;
    }

    public void setMsgType(Message.MessageType msgType) {
        this.msgType = msgType;
    }

    public Long getRawMsgTime() {
        return rawMsgTime;
    }

    public void setRawMsgTime(Long rawMsgTime) {
        this.rawMsgTime = rawMsgTime;
    }

    public Integer getMsgSeq() {
        return msgSeq;
    }

    public void setMsgSeq(Integer msgSeq) {
        this.msgSeq = msgSeq;
    }

    public Long getReplyToMessageId() {
        return replyToMessageId;
    }

    public void setReplyToMessageId(Long replyToMessageId) {
        this.replyToMessageId = replyToMessageId;
    }

    public String getReplyToNickname() {
        return replyToNickname;
    }

    public void setReplyToNickname(String replyToNickname) {
        this.replyToNickname = replyToNickname;
    }

    public String getReplyToContent() {
        return replyToContent;
    }

    public void setReplyToContent(String replyToContent) {
        this.replyToContent = replyToContent;
    }

    public String getForwardContent() {
        return forwardContent;
    }

    public void setForwardContent(String forwardContent) {
        this.forwardContent = forwardContent;
    }

    public String getMiniAppContent() {
        return miniAppContent;
    }

    public void setMiniAppContent(String miniAppContent) {
        this.miniAppContent = miniAppContent;
    }

    public List<MediaTaskPayload> getMediaTasks() {
        return mediaTasks;
    }

    public void setMediaTasks(List<MediaTaskPayload> mediaTasks) {
        this.mediaTasks = mediaTasks;
    }
}
