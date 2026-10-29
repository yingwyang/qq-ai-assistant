package com.qqai.adapter.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqai.capability.im.ImLoginInfo;
import com.qqai.capability.im.ImSendResult;
import com.qqai.capability.im.ImUploadResult;
import com.qqai.service.NapCatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** NapCat 适配器只做委托与结果类型转换；不改变任何调用语义。 */
class NapCatImAdapterTest {

    private NapCatService napCatService;
    private NapCatImAdapter adapter;

    @BeforeEach
    void setUp() {
        napCatService = mock(NapCatService.class);
        adapter = new NapCatImAdapter(napCatService);
    }

    @Test
    @DisplayName("发群消息：透传参数并把结果转成 ImSendResult")
    void sendGroupMessageDelegates() {
        given(napCatService.sendGroupMessage("g1", "hi"))
                .willReturn(new NapCatService.SendGroupMessageResult(true, "m1", null));

        ImSendResult result = adapter.sendGroupMessage("g1", "hi");

        assertTrue(result.success());
        assertEquals("m1", result.messageId());
        verify(napCatService).sendGroupMessage("g1", "hi");
    }

    @Test
    @DisplayName("上传群文件：空结果保持 null，成功结果转换 messageId")
    void uploadConverts() {
        given(napCatService.uploadGroupFile("g1", "/tmp/a", "a"))
                .willReturn(new NapCatService.UploadGroupFileResult(true, "u1", "a", 200, null));

        ImUploadResult result = adapter.uploadGroupFile("g1", "/tmp/a", "a");

        assertTrue(result.success());
        assertEquals("u1", result.messageId());
    }

    @Test
    @DisplayName("登录信息：user_id/nickname 转成 ImLoginInfo")
    void loginInfoConverts() throws Exception {
        var data = new ObjectMapper().createObjectNode();
        data.put("user_id", 2488130337L);
        data.put("nickname", "陰陽");
        given(napCatService.getLoginInfo()).willReturn(data);

        ImLoginInfo info = adapter.loginInfo();

        assertNotNull(info);
        assertEquals(2488130337L, info.qq());
        assertEquals("陰陽", info.nickname());
    }

    @Test
    @DisplayName("登录/二维码/成员列表透传")
    void loginAndMembers() throws Exception {
        given(napCatService.checkLoginStatus()).willReturn(true);
        given(napCatService.getQrCodePath()).willReturn("qr.png");

        assertTrue(adapter.checkLoginStatus());
        assertEquals("qr.png", adapter.getQrCodePath());
        verify(napCatService).checkLoginStatus();
        verify(napCatService).getQrCodePath();
    }
}
