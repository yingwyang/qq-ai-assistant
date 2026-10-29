package com.qqai.adapter.im;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.qqai.capability.im.ImGateway;
import com.qqai.capability.im.ImSendResult;
import com.qqai.capability.im.ImUploadResult;
import com.qqai.service.NapCatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** NapCat 的 ImGateway 实现：只做委托与结果类型转换。 */
@Service
public class NapCatImAdapter implements ImGateway {

    private static final Logger log = LoggerFactory.getLogger(NapCatImAdapter.class);

    private final NapCatService napCatService;

    public NapCatImAdapter(NapCatService napCatService) {
        this.napCatService = napCatService;
    }

    @Override
    public boolean checkLoginStatus() throws Exception {
        return napCatService.checkLoginStatus();
    }

    @Override
    public String getLoginQrCode() throws Exception {
        return napCatService.getLoginQrCode();
    }

    @Override
    public String getQrCodePath() throws Exception {
        return napCatService.getQrCodePath();
    }

    @Override
    public String getWebUiUrl() {
        return napCatService.getWebUiUrl();
    }

    @Override
    public boolean sendPrivateMessage(String qq, String message) {
        return napCatService.sendPrivateMessage(qq, message);
    }

    @Override
    public ImSendResult sendGroupMessage(String groupId, String text) {
        return toResult(napCatService.sendGroupMessage(groupId, text));
    }

    @Override
    public ImSendResult sendGroupSegments(String groupId, ArrayNode segments) {
        return toResult(napCatService.sendGroupSegments(groupId, segments));
    }

    @Override
    public ImUploadResult uploadGroupFile(String groupId, String filePath, String fileName) {
        NapCatService.UploadGroupFileResult result = napCatService.uploadGroupFile(groupId, filePath, fileName);
        if (result == null) return null;
        return new ImUploadResult(result.isSuccess(), result.getMessageId(), result.getErrorMessage());
    }

    @Override
    public ArrayNode getGroupMemberList(String groupId, boolean noCache) throws Exception {
        return napCatService.getGroupMemberList(groupId, noCache);
    }

    @Override
    public String getStrangerNickname(String qqNumber) {
        return napCatService.getStrangerNickname(qqNumber);
    }

    @Override
    public void checkAndAutoConfigureNewQq() throws Exception {
        try {
            napCatService.checkAndAutoConfigureNewQq();
        } catch (Exception e) {
            log.warn("NapCat 自动配置失败: {}", e.getMessage());
            throw e;
        }
    }

    private static ImSendResult toResult(NapCatService.SendGroupMessageResult result) {
        if (result == null) return null;
        return new ImSendResult(result.isSuccess(), result.getMessageId(), result.getErrorMessage());
    }
}
