package com.evops.quota.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日结对平报告（运维数据）：每账户每日一行，重跑覆盖原行。
 * 对平口径：账户占用=未结预占合计、账户实扣=实扣流水合计、
 * 每笔实扣必有预占来源、无超时未还预占、占用+实扣不超总额。
 */
@Getter
@Setter
@TableName("t_quota_recon")
public class QuotaRecon extends BaseEntity {

    public static final String BALANCED = "BALANCED";
    public static final String MISMATCH = "MISMATCH";

    /** 对平日期（运行日） */
    private LocalDate reconDate;
    private Long accountId;
    private String storeCode;
    /** 应有占用 = 未结预占合计 */
    private BigDecimal reservedExpected;
    /** 实有占用 = 账户占用中 */
    private BigDecimal reservedActual;
    /** 应有实扣 = 实扣流水合计 */
    private BigDecimal usedExpected;
    /** 实有实扣 = 账户已实扣 */
    private BigDecimal usedActual;
    /** 超时未还预占数（容忍扫描延迟后仍存在） */
    private Integer danglingHoldCount;
    /** 无预占来源的实扣流水数（理论恒 0） */
    private Integer confirmWithoutReserve;
    /** BALANCED / MISMATCH */
    private String reconResult;
    /** 差异明细（预占单号/流水号/差额） */
    private String detailJson;
}
