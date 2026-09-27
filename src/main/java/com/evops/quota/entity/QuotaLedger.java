package com.evops.quota.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 额度明细流水（只追加，可对账）。
 * 预占/实扣/释放/超时还每个动作一行，带动作后账户快照（reservedAfter/usedAfter），
 * 按 biz_date（业务日）日结对平：当日 RESERVE 合计 = CONFIRM+RELEASE+EXPIRE 合计 + 期末未结余额。
 */
@Getter
@Setter
@TableName("t_quota_ledger")
public class QuotaLedger extends BaseEntity {

    public static final String RESERVE = "RESERVE";
    public static final String CONFIRM = "CONFIRM";
    public static final String RELEASE = "RELEASE";
    public static final String EXPIRE = "EXPIRE";

    /** 流水号，全库唯一 */
    private String ledgerNo;
    private String storeCode;
    /** 关联预占单号 */
    private String reservationNo;
    /** RESERVE / CONFIRM / RELEASE / EXPIRE */
    private String entryType;
    private BigDecimal amount;
    /** 对账业务日（取业务时间） */
    private LocalDate bizDate;
    /** 动作后账户预占余额（快照） */
    private BigDecimal reservedAfter;
    /** 动作后账户实扣累计（快照） */
    private BigDecimal usedAfter;
    /** 关联业务单号（随预占单） */
    private String bizRef;
    private String operRemark;
}
