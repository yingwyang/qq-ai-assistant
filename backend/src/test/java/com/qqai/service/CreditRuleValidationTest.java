package com.qqai.service;

import com.qqai.entity.CreditRule;
import com.qqai.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 积分规则保存前校验（{@code CreditRuleService.validate}）。
 *
 * <p>为什么要单测：这三个 JSON 字段（模型倍率 / 分析类型倍率 / 阶梯折扣）以前是纯字符串，
 * 管理员写错一个逗号或把折扣写成 1.5 都能存进去，计费会被打穿；
 * 而且校验原本写在 controller 里、用的是 {@code BizException(String)}（默认 code=500），
 * 用户输入错误会显示成「服务器错误」。这里锁住「值不合法 → 400 + 可读文案」。</p>
 */
class CreditRuleValidationTest {

    private final CreditRuleService service = new CreditRuleService();

    private void assertRejected(CreditRule rule, String keyword) {
        BizException ex = assertThrows(BizException.class, () -> service.validate(rule));
        assertEquals(400, ex.getCode(), "校验失败必须是 400（不是 500）");
        assertTrue(ex.getMessage().contains(keyword),
                "错误文案应包含「" + keyword + "」，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("默认规则能通过校验")
    void defaultRulePasses() {
        CreditRule rule = new CreditRule();   // 实体默认值 = 出厂默认
        assertDoesNotThrow(() -> service.validate(rule));
    }

    @Test
    @DisplayName("三个 JSON 字段：非法 JSON / 非对象 / 值超范围都要被拒")
    void jsonFields() {
        CreditRule bad1 = new CreditRule();
        bad1.setModelRates("{\"default\": 1.0,");
        assertRejected(bad1, "模型费率映射");

        CreditRule bad2 = new CreditRule();
        bad2.setModelRates("[1.0, 2.0]");
        assertRejected(bad2, "模型费率映射");

        CreditRule bad3 = new CreditRule();
        bad3.setModelRates("{\"gpt-4\": \"贵\"}");
        assertRejected(bad3, "倍率必须是数字");

        CreditRule bad4 = new CreditRule();
        bad4.setAnalyzeTypeRates("{\"summary\": -1}");
        assertRejected(bad4, "不能为负");

        CreditRule bad5 = new CreditRule();
        bad5.setTieredDiscountThresholds("{\"abc\": 0.9}");
        assertRejected(bad5, "阈值必须是正整数");

        CreditRule bad6 = new CreditRule();
        bad6.setTieredDiscountThresholds("{\"1000\": 1.5}");
        assertRejected(bad6, "0.01~1.0");

        CreditRule ok = new CreditRule();
        ok.setModelRates("{\"default\":1.0,\"deepseek-v3\":0.8}");
        ok.setAnalyzeTypeRates("{\"default\":1.0,\"summary\":1.2}");
        ok.setTieredDiscountThresholds("{\"1000\":0.95,\"5000\":0.9}");
        assertDoesNotThrow(() -> service.validate(ok));
    }

    @Test
    @DisplayName("折扣必须落在 0.01~1.0：1.5 与 0 都要被拒")
    void discountRange() {
        CreditRule tooBig = new CreditRule();
        tooBig.setSmallMonthCardDiscount(1.5);
        assertRejected(tooBig, "小月卡折扣");

        CreditRule tooSmall = new CreditRule();
        tooSmall.setAllTierDiscount(0.0);
        assertRejected(tooSmall, "ALL 状态折扣");
    }

    @Test
    @DisplayName("倍率与粒度：超配额倍率 <1、tokenUnit <=0 要被拒")
    void rateAndUnit() {
        CreditRule r1 = new CreditRule();
        r1.setOvertaxRate(0.5);
        assertRejected(r1, "超配额倍率");

        CreditRule r2 = new CreditRule();
        r2.setTokenUnit(0);
        assertRejected(r2, "tokenUnit");

        CreditRule r3 = new CreditRule();
        r3.setTtsCharsPerCredit(0);
        assertRejected(r3, "TTS");

        CreditRule r4 = new CreditRule();
        r4.setPlanDurationDays(0);
        assertRejected(r4, "套餐时长");
    }

    @Test
    @DisplayName("负数积分字段被拒（新人奖励 / 套餐积分 / 图片费用等）")
    void negatives() {
        CreditRule r1 = new CreditRule();
        r1.setNewUserBonus(-1);
        assertRejected(r1, "新人奖励");

        CreditRule r2 = new CreditRule();
        r2.setPlanUltraCredit(-100);
        assertRejected(r2, "Ultra 积分");

        CreditRule r3 = new CreditRule();
        r3.setImageExtraCost(-5);
        assertRejected(r3, "图片额外费用");

        CreditRule r4 = new CreditRule();
        r4.setDailyCapCost(-1);
        assertRejected(r4, "每日封顶");
    }
}
