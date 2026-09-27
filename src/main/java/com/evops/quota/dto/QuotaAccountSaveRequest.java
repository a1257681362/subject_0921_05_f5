package com.evops.quota.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 门店额度账户开户请求。
 */
@Getter
@Setter
public class QuotaAccountSaveRequest {
    @NotBlank(message = "不能为空")
    @Size(max = 32, message = "长度不能超过32")
    private String storeCode;

    @NotNull(message = "不能为空")
    @DecimalMin(value = "0.00", message = "不能为负")
    private BigDecimal totalQuota;

    @Size(max = 255)
    private String remark;
}
