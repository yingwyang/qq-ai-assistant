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

    /** QQ 文件 ID（用于 get_file API 调用） */
    private String fileId;

    /**
     * 来源是否可信（消息段已佐证该媒体段真实存在）。
     * 视频消息的 url 往往是 QQ 本地缓存绝对路径（如 ...\Tencent Files\<qq>\nt_qq\nt_data\Video\...mp4），
     * 这类路径不在 uploads 目录内,但来自 NapCat 的真实消息段,群成员无法伪造,
     * 因此可信来源允许直接复制;来自未佐证 CQ 码的路径仍受 uploads 越界保护。
     */
    private boolean trustedSource;

    public MediaTaskPayload() {
    }

    public MediaTaskPayload(String rawMessage, String url, String groupId, String mediaType, String extension) {
        this.rawMessage = rawMessage;
        this.url = url;
        this.groupId = groupId;
        this.mediaType = mediaType;
        this.extension = extension;
    }

    public MediaTaskPayload(String rawMessage, String url, String groupId, String mediaType, String extension, String fileId) {
        this.rawMessage = rawMessage;
        this.url = url;
        this.groupId = groupId;
        this.mediaType = mediaType;
        this.extension = extension;
        this.fileId = fileId;
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

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public boolean isTrustedSource() {
        return trustedSource;
    }

    public void setTrustedSource(boolean trustedSource) {
        this.trustedSource = trustedSource;
    }

    @Override
    public String toString() {
        return "MediaTaskPayload{messageId=" + messageId
                + ", mediaType=" + mediaType
                + ", extension=" + extension
                + ", groupId=" + groupId
                + ", fileId=" + fileId
                + ", url=" + url + '}';
    }
}
