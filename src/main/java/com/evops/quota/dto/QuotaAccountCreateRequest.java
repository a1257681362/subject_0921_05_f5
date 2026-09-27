package com.evops.quota.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 门店额度建户。storeCode 全库唯一，重复建户由唯一约束兜底。
 */
@Getter
@Setter
public class QuotaAccountCreateRequest {

    @NotBlank(message = "门店编码不能为空")
    @Size(max = 32)
    private String storeCode;

    @NotNull(message = "核定总额度不能为空")
    @DecimalMin(value = "0.00", message = "核定总额度不能为负")
    @Digits(integer = 12, fraction = 2, message = "额度最多两位小数")
    private BigDecimal totalQuota;
}
