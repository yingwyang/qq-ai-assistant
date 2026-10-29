package com.qqai.capability.im;

/** 群文件上传结果（与实现无关）。 */
public record ImUploadResult(boolean success, String messageId, String errorMessage) {
}
