package com.qqai.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理员补单（直接创建已支付订单）请求体。
 *
 * 安全约束：这是「不发钱也能造权益」的入口，所有可影响资金的字段都必须有边界，
 * 避免管理员（或拿着管理员令牌的攻击者）用一次请求造出天量积分/超长有效期。
 * priceCents / pointsGranted / durationDays 为空时表示「按套餐规则取值」。
 */
public record ManualCreateOrderRequest(

        @NotNull(message = "userId 不能为空")
        @Min(value = 1, message = "userId 不合法")
        Long userId,

        @NotBlank(message = "planCode 不能为空")
        @Size(max = 64, message = "planCode 过长")
        String planCode,

        /** 价格覆盖值，单位「分」；为空表示按套餐价 */
        @Min(value = 0, message = "priceCents 不能为负")
        @Max(value = 100_000_000, message = "priceCents 过大（上限 100 万分 = 1 万元）")
        Long priceCents,

        /** 积分覆盖值；为空表示按套餐积分。上限 1000 万，足够运营使用，也能挡住误操作 */
        @Min(value = 0, message = "pointsGranted 不能为负")
        @Max(value = 10_000_000, message = "pointsGranted 过大（上限 1000 万）")
        Integer pointsGranted,

        /** 有效期覆盖值（天）；为空表示按套餐时长 */
        @Min(value = 1, message = "durationDays 至少为 1 天")
        @Max(value = 3650, message = "durationDays 最多 3650 天")
        Integer durationDays,

        @Size(max = 64, message = "orderNo 过长")
        String orderNo,

        @Size(max = 200, message = "remark 最多 200 字")
        String remark
) {
}
