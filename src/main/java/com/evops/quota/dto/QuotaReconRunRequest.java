package com.evops.quota.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.Size;

/**
 * 手工触发日结对平请求：可指定门店，缺省全部门店。
 */
@Getter
@Setter
public class QuotaReconRunRequest {
    @Size(max = 32)
    private String storeCode;
}
