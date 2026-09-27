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
 * 额度预占。占用 40 分钟内有效，超时自动归还；实扣以预占为前提。
 */
@Getter
@Setter
public class QuotaReserveRequest {

    @NotBlank(message = "门店编码不能为空")
    @Size(max = 32)
    private String storeCode;

    @NotNull(message = "预占金额不能为空")
    @DecimalMin(value = "0.01", message = "预占金额必须大于零")
    @Digits(integer = 12, fraction = 2, message = "金额最多两位小数")
    private BigDecimal amount;

    /** 关联业务单号（如加工单号），可空 */
    @Size(max = 64)
    private String bizRef;
}
