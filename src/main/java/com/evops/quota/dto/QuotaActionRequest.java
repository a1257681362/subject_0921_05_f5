package com.evops.quota.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 预占单动作：实扣（confirm）/ 手工释放（release），凭预占单号。
 */
@Getter
@Setter
public class QuotaActionRequest {

    @NotBlank(message = "预占单号不能为空")
    @Size(max = 64)
    private String reservationNo;

    @Size(max = 255)
    private String remark;
}
