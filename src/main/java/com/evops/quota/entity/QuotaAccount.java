package com.evops.quota.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 门店额度账户。store_code 全库唯一；
 * 可用额度 = totalQuota - reservedAmount - usedAmount。
 * 禁止物理删除；额度只经三段式动作（预占/实扣/释放/超时还）变动，逐笔写流水。
 */
@Getter
@Setter
@TableName("t_quota_account")
public class QuotaAccount extends BaseEntity {
    /** 门店编码，全库唯一 */
    private String storeCode;
    /** 核定总额度 */
    private BigDecimal totalQuota;
    /** 预占中（占用未结） */
    private BigDecimal reservedAmount;
    /** 已实扣累计 */
    private BigDecimal usedAmount;
}
