package com.qqai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.mock;

/**
 * QQ 绑定验证码服务测试。
 *
 * <p>背景：原先只有「单个 QQ 每天 10 次」，没有账号维度限制——登录用户可以拿机器人给任意 QQ 发私信
 * （群发通道）；且三个内存 Map 只靠"恰好读到空"清理，键随 QQ 数无限增长。</p>
 *
 * <p>测试用不同的 QQ/账号编号避免用例间共享单例内存状态。</p>
 */
class QqVerificationServiceTest {

    private static final Pattern CODE_PATTERN = Pattern.compile("(\\d{6})");

    private QqVerificationService service;
    private NapCatService napCat;

    @BeforeEach
    void setUp() {
        napCat = mock(NapCatService.class);
        willReturn(true).given(napCat).sendPrivateMessage(anyString(), anyString());

        service = new QqVerificationService();
        ReflectionTestUtils.setField(service, "napCatService", napCat);
    }

    @Test
    @DisplayName("正常发送：返回 SENT，并可通过 hasValidCode 查到未过期验证码")
    void sendsCodeSuccessfully() {
        assertEquals(QqVerificationService.SendResult.SENT,
                service.sendVerificationCode("100001", 1L));
        assertTrue(service.hasValidCode("100001"));
    }

    @Test
    @DisplayName("NapCat 发送失败 → SEND_FAILED，且不留下有效验证码")
    void reportsSendFailure() {
        NapCatService failing = mock(NapCatService.class);
        willReturn(false).given(failing).sendPrivateMessage(anyString(), anyString());
        ReflectionTestUtils.setField(service, "napCatService", failing);

        assertEquals(QqVerificationService.SendResult.SEND_FAILED,
                service.sendVerificationCode("100002", 2L));
        assertFalse(service.hasValidCode("100002"), "发送失败不应产生可用验证码");
    }

    @Test
    @DisplayName("账号级每日限额：同一账号发满 10 次后第 11 次被限流（换 QQ 也不放行）")
    void perUserDailyLimitIsEnforced() {
        long userId = 9001L;
        for (int i = 0; i < 10; i++) {
            // 每次换一个 QQ，确保被拦的是账号维度而不是 QQ 维度
            assertEquals(QqVerificationService.SendResult.SENT,
                    service.sendVerificationCode("2000" + i, userId), "第 " + (i + 1) + " 次应放行");
        }
        assertEquals(QqVerificationService.SendResult.RATE_LIMITED,
                service.sendVerificationCode("299999", userId),
                "账号级限额应拦住『换 QQ 继续群发』");
    }

    @Test
    @DisplayName("单 QQ 每日限额：同一 QQ 发满 10 次后第 11 次被限流（换账号也不放行）")
    void perQqDailyLimitIsEnforced() {
        String qq = "300001";
        for (int i = 0; i < 10; i++) {
            assertEquals(QqVerificationService.SendResult.SENT,
                    service.sendVerificationCode(qq, 10_000L + i), "第 " + (i + 1) + " 次应放行");
        }
        assertEquals(QqVerificationService.SendResult.RATE_LIMITED,
                service.sendVerificationCode(qq, 10_999L),
                "单 QQ 限额应拦住『换账号继续骚扰同一个 QQ』");
    }

    @Test
    @DisplayName("验证码校验：错误 → INVALID；正确 → SUCCESS；成功后立即失效")
    void verifiesCodeCorrectly() {
        String qq = "400001";
        assertEquals(QqVerificationService.SendResult.SENT, service.sendVerificationCode(qq, 20L));
        String code = captureLastSentCode(qq);

        assertEquals(QqVerificationService.VerificationResult.INVALID, service.verifyCode(qq, "000000"));
        assertEquals(QqVerificationService.VerificationResult.SUCCESS, service.verifyCode(qq, code));
        assertEquals(QqVerificationService.VerificationResult.NO_CODE, service.verifyCode(qq, code),
                "验证成功后验证码应立即失效（不可重放）");
    }

    @Test
    @DisplayName("爆破防护：同一验证码连续错 10 次即作废")
    void invalidatesCodeAfterTooManyFailedAttempts() {
        String qq = "400002";
        service.sendVerificationCode(qq, 21L);
        String realCode = captureLastSentCode(qq);
        String wrong = realCode.equals("111111") ? "222222" : "111111";

        for (int i = 0; i < 9; i++) {
            assertEquals(QqVerificationService.VerificationResult.INVALID, service.verifyCode(qq, wrong),
                    "第 " + (i + 1) + " 次错误应为 INVALID");
        }
        assertEquals(QqVerificationService.VerificationResult.EXPIRED, service.verifyCode(qq, wrong),
                "第 10 次错误应触发作废");
        assertFalse(service.hasValidCode(qq), "作废后不应再有可用验证码");
        assertEquals(QqVerificationService.VerificationResult.NO_CODE, service.verifyCode(qq, realCode),
                "作废后即使输入正确验证码也不应通过");
    }

    // ===================== 辅助方法 =====================

    /** 从发给 NapCat 的私信文本里取出验证码（暴露的私有状态用行为断言代替） */
    private String captureLastSentCode(String qqNumber) {
        org.mockito.ArgumentCaptor<String> messages = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(napCat, org.mockito.Mockito.atLeastOnce())
                .sendPrivateMessage(org.mockito.ArgumentMatchers.eq(qqNumber), messages.capture());
        Matcher matcher = CODE_PATTERN.matcher(messages.getValue());
        assertTrue(matcher.find(), "私信内容里应包含 6 位验证码: " + messages.getValue());
        return matcher.group(1);
    }
}
