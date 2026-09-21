package com.qqai.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理员手动调整用户积分请求体。
 *
 * amount 为有符号整数：正数发放、负数扣减；0 无意义故由 @Min/@Max 之外的业务层拦截，
 * 这里给出上下界避免一次调账把余额抬到天文数字或扣成极端负值。
 */
public record CreditAdjustRequest(

        @NotNull(message = "userId 不能为空")
        @Min(value = 1, message = "userId 不合法")
        Long userId,

        @NotNull(message = "amount 不能为空")
        @Min(value = -10_000_000, message = "amount 过小（下限 -1000 万）")
        @Max(value = 10_000_000, message = "amount 过大（上限 1000 万）")
        Integer amount,

        @Size(max = 200, message = "reason 最多 200 字")
        String reason
) {
}
