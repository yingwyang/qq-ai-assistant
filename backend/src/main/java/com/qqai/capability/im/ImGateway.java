package com.qqai.capability.im;

import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * IM 网关能力契约：登录、发送、群/成员查询。
 *
 * <p>范围约定：只覆盖「出站 + 登录」；入站 webhook 的事件处理仍留在既有
 * RootWebhookController（其内部依赖的媒体/转发工具也保留在 NapCatService）。
 * 返回 ArrayNode 是因为既有调用方按 OneBot JSON 形状消费，Phase 3 不做全量 DTO 化。</p>
 */
public interface ImGateway {

    boolean checkLoginStatus() throws Exception;

    String getLoginQrCode() throws Exception;

    String getQrCodePath() throws Exception;

    String getWebUiUrl();

    boolean sendPrivateMessage(String qq, String message);

    ImSendResult sendGroupMessage(String groupId, String text);

    ImSendResult sendGroupSegments(String groupId, ArrayNode segments);

    ImUploadResult uploadGroupFile(String groupId, String filePath, String fileName);

    ArrayNode getGroupMemberList(String groupId, boolean noCache) throws Exception;

    String getStrangerNickname(String qqNumber);

    void checkAndAutoConfigureNewQq() throws Exception;
}
