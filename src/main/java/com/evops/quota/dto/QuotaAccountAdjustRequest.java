package com.evops.quota.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 调整门店核定总额度。调整后总额度不得低于当前预占加已实扣（QUOTA_TOTAL_TOO_LOW）。
 */
@Getter
@Setter
public class QuotaAccountAdjustRequest {

    @NotNull(message = "核定总额度不能为空")
    @DecimalMin(value = "0.00", message = "核定总额度不能为负")
    @Digits(integer = 12, fraction = 2, message = "额度最多两位小数")
    private BigDecimal totalQuota;
}
