package com.evops.quota.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.Size;

/**
 * 预占确认/释放请求体，备注可选。
 */
@Getter
@Setter
public class QuotaHoldActionRequest {
    @Size(max = 255)
    private String remark;
}
