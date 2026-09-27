package com.evops.quota.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 额度预占请求。可挂业务单号（如加工单号）便于追溯与按单聚合日志。
 */
@Getter
@Setter
public class QuotaHoldCreateRequest {
    @NotBlank(message = "不能为空")
    @Size(max = 32, message = "长度不能超过32")
    private String storeCode;

    @NotNull(message = "不能为空")
    @DecimalMin(value = "0.01", message = "预占金额必须为正")
    private BigDecimal amount;

    /** 关联业务类型（如 PROCESS_ORDER），可空 */
    @Size(max = 32)
    private String bizType;

    /** 关联业务单号（如加工单号），可空 */
    @Size(max = 64)
    private String bizNo;

    @Size(max = 255)
    private String remark;
}
