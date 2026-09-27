package com.evops.quota.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 门店额度账户：每门店一户（store_code 唯一），金额制。
 * 可用额度 = 总额度 - 占用中 - 已实扣（不落库，查询时计算）。
 * 余额变动全部走条件更新（占用+实扣+本次 <= 总额），并发下不会超占。
 */
@Getter
@Setter
@TableName("t_quota_account")
public class QuotaAccount extends BaseEntity {
    /** 门店编码，一户一码 */
    private String storeCode;
    /** 总额度 */
    private BigDecimal totalQuota;
    /** 占用中（预占未确认/未释放） */
    private BigDecimal reservedAmount;
    /** 已实扣 */
    private BigDecimal usedAmount;
    private String remark;

    /** 可用额度（查询时计算，不落库） */
    @TableField(exist = false)
    private BigDecimal availableAmount;
}
