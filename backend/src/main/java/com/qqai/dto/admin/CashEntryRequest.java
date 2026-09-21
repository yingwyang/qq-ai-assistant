package com.qqai.dto.admin;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 管理员手工记一笔现金账（模拟现金账，积分不变）。
 *
 * direction 为空时按 IN 处理（保持原行为）；金额单位「元」，最多两位小数、上限 9999999。
 */
public record CashEntryRequest(

        @Pattern(regexp = "IN|OUT|in|out", message = "direction 只能是 IN 或 OUT")
        String direction,

        @jakarta.validation.constraints.NotNull(message = "金额不能为空")
        @DecimalMin(value = "0.01", message = "金额必须大于 0")
        @DecimalMax(value = "9999999", message = "金额过大（上限 9999999 元）")
        @Digits(integer = 7, fraction = 2, message = "金额最多两位小数")
        BigDecimal amount,

        @Size(max = 200, message = "remark 最多 200 字")
        String remark,

        @Size(max = 32, message = "category 过长")
        String category,

        @Positive(message = "userId 不合法")
        Long userId
) {
}
