package com.qqai.dto.webhook;

import java.io.Serializable;

/**
 * 媒体异步任务载荷。
 *
 * 由 RootWebhookController 在消息入库后投递到 RabbitMQ：
 *  - 图片/视频 → media.download.queue
 *  - 语音（含转码）→ voice.transcode.queue
 *
 * 消费者据此调 {@link com.qqai.service.MediaDownloadService#downloadMediaToLocal} 完成下载/转码，
 * 回填 message.content 并清除 mediaPending。
 */
public class MediaTaskPayload implements Serializable {

    /** 数据库 messages.id（消费者回填用） */
    private Long messageId;

    /** 完整 CQ 码（传给 downloadMediaToLocal） */
    private String rawMessage;

    /** 从 CQ 码提取的媒体 URL（调试/日志用） */
    private String url;

    /** 群号 */
    private String groupId;

    /** 媒体类型：images / voice / video（对应 MediaDownloadService 的子目录） */
    private String mediaType;

    /** 文件扩展名：.jpg / .amr / .mp4 */
    private String extension;

    public MediaTaskPayload() {
    }

    public MediaTaskPayload(String rawMessage, String url, String groupId, String mediaType, String extension) {
        this.rawMessage = rawMessage;
        this.url = url;
        this.groupId = groupId;
        this.mediaType = mediaType;
        this.extension = extension;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public String getRawMessage() {
        return rawMessage;
    }

    public void setRawMessage(String rawMessage) {
        this.rawMessage = rawMessage;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    public String getExtension() {
        return extension;
    }

    public void setExtension(String extension) {
        this.extension = extension;
    }

    @Override
    public String toString() {
        return "MediaTaskPayload{messageId=" + messageId
                + ", mediaType=" + mediaType
                + ", extension=" + extension
                + ", groupId=" + groupId
                + ", url=" + url + '}';
    }
}
