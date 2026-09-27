package com.evops.quota.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 额度日结对平报告（按门店 + 业务日）：
 * 当日预占合计 = 当日实扣+释放+过期合计 + 期末未结余额；
 * 账户预占余额 = 全量未结预占合计；账户实扣累计 = 全量实扣流水合计。
 * 三项全部相等 balanced=true，否则 mismatches 列出差异。
 */
@Getter
@Setter
public class ReconcileReport {
    private String storeCode;
    private LocalDate bizDate;

    /** 当日预占合计 */
    private BigDecimal reserveSum = BigDecimal.ZERO;
    /** 当日实扣合计 */
    private BigDecimal confirmSum = BigDecimal.ZERO;
    /** 当日手工释放合计 */
    private BigDecimal releaseSum = BigDecimal.ZERO;
    /** 当日超时归还合计 */
    private BigDecimal expireSum = BigDecimal.ZERO;
    /** 当日预占期末未结余额（reserveSum - confirmSum - releaseSum - expireSum） */
    private BigDecimal openSum = BigDecimal.ZERO;
    /** 当日预占的单据中至今仍未结的实际合计（应与 openSum 相等） */
    private BigDecimal dayOpenActual = BigDecimal.ZERO;

    /** 账户当前预占余额 */
    private BigDecimal accountReserved = BigDecimal.ZERO;
    /** 账户当前实扣累计 */
    private BigDecimal accountUsed = BigDecimal.ZERO;
    /** 全量未结预占单据合计（应与 accountReserved 相等） */
    private BigDecimal openTotal = BigDecimal.ZERO;
    /** 全量实扣流水合计（应与 accountUsed 相等） */
    private BigDecimal confirmTotal = BigDecimal.ZERO;

    private boolean balanced;
    private List<String> mismatches = new ArrayList<>();
}
