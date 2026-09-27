package com.evops.quota.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 调整门店总额度请求。调整后总额度不得低于当前占用加已实扣。
 */
@Getter
@Setter
public class QuotaAdjustRequest {
    @NotNull(message = "不能为空")
    @DecimalMin(value = "0.00", message = "不能为负")
    private BigDecimal totalQuota;

    @Size(max = 255)
    private String remark;
}
