package com.evops.quota.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BizCode;
import com.evops.common.BizException;
import com.evops.common.RequestContext;
import com.evops.quota.dto.QuotaAccountAdjustRequest;
import com.evops.quota.dto.QuotaAccountCreateRequest;
import com.evops.quota.dto.QuotaActionRequest;
import com.evops.quota.dto.QuotaReserveRequest;
import com.evops.quota.dto.ReconcileReport;
import com.evops.quota.entity.QuotaAccount;
import com.evops.quota.entity.QuotaLedger;
import com.evops.quota.entity.QuotaReservation;
import com.evops.quota.mapper.QuotaAccountMapper;
import com.evops.quota.mapper.QuotaLedgerMapper;
import com.evops.quota.mapper.QuotaReservationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 门店额度三段式管理：预占（RESERVE）→ 实扣（CONFIRM）/ 释放（RELEASE）/ 超时还（EXPIRE）。
 * <p>
 * 约定：
 * <ul>
 *   <li>占用 40 分钟内有效（expire_time = 预占业务时间 + 40 分钟），超时由定时任务自动归还；</li>
 *   <li>实扣必须以有效预占为前提：预占单存在、状态 RESERVED、业务时间未过 expire_time；</li>
 *   <li>释放可手工触发；过期未结的预占按超时还结算（EXPIRE），与定时任务口径一致；</li>
 *   <li>账户余额变动全部走带守卫条件的 UPDATE（可用额度不为负、预占余额不为负），
 *       并发不超占；账户 + 预占单 + 流水同一事务，要么全部生效要么全部不见；</li>
 *   <li>每个动作写一条 t_quota_ledger 流水（只追加，带动作后账户快照），按 biz_date 日结对平。</li>
 * </ul>
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    @Resource
    private QuotaAccountMapper accountMapper;
    @Resource
    private QuotaReservationMapper reservationMapper;
    @Resource
    private QuotaLedgerMapper ledgerMapper;

    /** 预占占用有效期（分钟）：超时自动归还 */
    @Value("${evops.quota.hold-minutes:40}")
    private long holdMinutes;

    // ---------------- 账户 ----------------

    /** 建户：storeCode 全库唯一，uk_quota_account_store 兜底并发重复 */
    @Transactional(rollbackFor = Exception.class)
    public QuotaAccount createAccount(QuotaAccountCreateRequest req) {
        QuotaAccount existing = accountMapper.selectOne(new LambdaQueryWrapper<QuotaAccount>()
                .eq(QuotaAccount::getStoreCode, req.getStoreCode().trim()));
        if (existing != null) {
            throw new BizException(BizCode.QUOTA_ACCOUNT_DUPLICATED);
        }
        QuotaAccount account = new QuotaAccount();
        account.setStoreCode(req.getStoreCode().trim());
        account.setTotalQuota(req.getTotalQuota());
        account.setReservedAmount(BigDecimal.ZERO);
        account.setUsedAmount(BigDecimal.ZERO);
        accountMapper.insert(account);
        account = accountMapper.selectById(account.getId());
        log.info("额度建户 store={} 总额度={}", account.getStoreCode(), account.getTotalQuota());
        return account;
    }

    /** 调整核定总额度：不得低于当前预占加已实扣（守卫条件兜底并发） */
    @Transactional(rollbackFor = Exception.class)
    public QuotaAccount adjustTotal(String storeCode, QuotaAccountAdjustRequest req) {
        QuotaAccount account = requireAccount(storeCode);
        int rows = accountMapper.update(null, new LambdaUpdateWrapper<QuotaAccount>()
                .eq(QuotaAccount::getStoreCode, account.getStoreCode())
                .set(QuotaAccount::getTotalQuota, req.getTotalQuota())
                .set(QuotaAccount::getUpdatedBy, RequestContext.operatorId())
                .set(QuotaAccount::getUpdatedTime, LocalDateTime.now())
                .set(QuotaAccount::getRequestNo, RequestContext.requestNo())
                .set(QuotaAccount::getBizTime, RequestContext.bizTime())
                .setSql("version_no = version_no + 1")
                .apply("reserved_amount + used_amount <= {0}", req.getTotalQuota()));
        if (rows == 0) {
            throw new BizException(BizCode.QUOTA_TOTAL_TOO_LOW);
        }
        account = accountMapper.selectById(account.getId());
        log.info("额度调整 store={} 新总额度={}", account.getStoreCode(), account.getTotalQuota());
        return account;
    }

    public QuotaAccount getAccount(String storeCode) {
        return requireAccount(storeCode);
    }

    // ---------------- 三段式：预占 ----------------

    /**
     * 预占：可用额度（总额-预占-实扣）足够才落预占单，守卫条件 UPDATE 兜底并发不超占；
     * 重复提交由 uk_quota_rsv_request 兜底（REQUEST_DUPLICATED）。
     */
    @Transactional(rollbackFor = Exception.class)
    public QuotaReservation reserve(QuotaReserveRequest req) {
        QuotaAccount account = requireAccount(req.getStoreCode());
        LocalDateTime bizTime = RequestContext.bizTime();
        BigDecimal amount = req.getAmount();

        int rows = accountMapper.update(null, new LambdaUpdateWrapper<QuotaAccount>()
                .eq(QuotaAccount::getStoreCode, account.getStoreCode())
                .set(QuotaAccount::getUpdatedBy, RequestContext.operatorId())
                .set(QuotaAccount::getUpdatedTime, LocalDateTime.now())
                .set(QuotaAccount::getRequestNo, RequestContext.requestNo())
                .set(QuotaAccount::getBizTime, bizTime)
                .setSql("reserved_amount = reserved_amount + {0}, version_no = version_no + 1", amount)
                .apply("total_quota - reserved_amount - used_amount >= {0}", amount));
        if (rows == 0) {
            throw new BizException(BizCode.QUOTA_NOT_ENOUGH);
        }

        QuotaReservation rsv = new QuotaReservation();
        rsv.setReservationNo(newNo("RSV"));
        rsv.setStoreCode(account.getStoreCode());
        rsv.setAmount(amount);
        rsv.setBizRef(trimToNull(req.getBizRef()));
        rsv.setRsvStatus(QuotaReservation.RESERVED);
        rsv.setExpireTime(bizTime.plusMinutes(holdMinutes));
        reservationMapper.insert(rsv);

        account = accountMapper.selectById(account.getId());
        writeLedger(account, rsv, QuotaLedger.RESERVE, bizTime, "额度预占");
        log.info("额度预占 store={} 单号={} 金额={} 有效期至={}", account.getStoreCode(),
                rsv.getReservationNo(), amount, rsv.getExpireTime());
        return rsv;
    }

    // ---------------- 三段式：实扣（以预占为前提） ----------------

    /** 实扣：必须持有效预占单（RESERVED 且业务时间未过有效期）；预占转实扣，账户预占减、实扣增 */
    @Transactional(rollbackFor = Exception.class)
    public QuotaReservation confirm(QuotaActionRequest req) {
        QuotaReservation rsv = requireReservation(req.getReservationNo());
        requireSettleable(rsv);
        LocalDateTime bizTime = RequestContext.bizTime();
        if (!rsv.getExpireTime().isAfter(bizTime)) {
            // 已过 40 分钟有效期：不允许再实扣，等定时任务按超时还结算
            throw new BizException(BizCode.QUOTA_RESERVATION_EXPIRED);
        }

        int rows = reservationMapper.update(null, new LambdaUpdateWrapper<QuotaReservation>()
                .eq(QuotaReservation::getId, rsv.getId())
                .eq(QuotaReservation::getRsvStatus, QuotaReservation.RESERVED)
                .set(QuotaReservation::getRsvStatus, QuotaReservation.CONFIRMED)
                .set(QuotaReservation::getConfirmedTime, bizTime)
                .set(QuotaReservation::getUpdatedBy, RequestContext.operatorId())
                .set(QuotaReservation::getUpdatedTime, LocalDateTime.now())
                .set(QuotaReservation::getRequestNo, RequestContext.requestNo())
                .set(QuotaReservation::getBizTime, bizTime)
                .setSql("version_no = version_no + 1"));
        if (rows == 0) {
            throw new BizException(BizCode.QUOTA_RESERVATION_STATUS_ILLEGAL, "预占单已被并发处理，请刷新后重试");
        }

        moveReserved(accountOf(rsv), rsv.getAmount(), true);
        QuotaAccount account = accountOf(rsv);
        writeLedger(account, rsv, QuotaLedger.CONFIRM, bizTime,
                req.getRemark() == null ? "额度实扣" : req.getRemark());
        log.info("额度实扣 store={} 单号={} 金额={}", rsv.getStoreCode(), rsv.getReservationNo(), rsv.getAmount());
        return reservationMapper.selectById(rsv.getId());
    }

    // ---------------- 三段式：释放（手工触发） ----------------

    /** 手工释放：有效期内按 RELEASE 结算；已过有效期按超时还（EXPIRE）结算，口径与定时任务一致 */
    @Transactional(rollbackFor = Exception.class)
    public QuotaReservation release(QuotaActionRequest req) {
        QuotaReservation rsv = requireReservation(req.getReservationNo());
        requireSettleable(rsv);
        LocalDateTime bizTime = RequestContext.bizTime();
        boolean expired = !rsv.getExpireTime().isAfter(bizTime);
        String toStatus = expired ? QuotaReservation.EXPIRED : QuotaReservation.RELEASED;
        String entryType = expired ? QuotaLedger.EXPIRE : QuotaLedger.RELEASE;
        String remark = req.getRemark() != null ? req.getRemark()
                : (expired ? "占用超时归还（手工触发结算）" : "手工释放预占");

        int rows = reservationMapper.update(null, new LambdaUpdateWrapper<QuotaReservation>()
                .eq(QuotaReservation::getId, rsv.getId())
                .eq(QuotaReservation::getRsvStatus, QuotaReservation.RESERVED)
                .set(QuotaReservation::getRsvStatus, toStatus)
                .set(QuotaReservation::getReleasedTime, bizTime)
                .set(QuotaReservation::getUpdatedBy, RequestContext.operatorId())
                .set(QuotaReservation::getUpdatedTime, LocalDateTime.now())
                .set(QuotaReservation::getRequestNo, RequestContext.requestNo())
                .set(QuotaReservation::getBizTime, bizTime)
                .setSql("version_no = version_no + 1"));
        if (rows == 0) {
            throw new BizException(BizCode.QUOTA_RESERVATION_STATUS_ILLEGAL, "预占单已被并发处理，请刷新后重试");
        }

        moveReserved(accountOf(rsv), rsv.getAmount(), false);
        QuotaAccount account = accountOf(rsv);
        writeLedger(account, rsv, entryType, bizTime, remark);
        log.info("额度{} store={} 单号={} 金额={}", expired ? "超时还" : "释放",
                rsv.getStoreCode(), rsv.getReservationNo(), rsv.getAmount());
        return reservationMapper.selectById(rsv.getId());
    }

    // ---------------- 超时自动还（定时任务逐单调，独立事务） ----------------

    /**
     * 单笔超时归还：仅当预占单仍 RESERVED 且系统时间已过有效期。
     * 独立事务，单笔失败不影响其他单（定时任务逐单调用）。
     *
     * @return true=本笔已归还
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean expireOneInNewTx(Long reservationId) {
        QuotaReservation rsv = reservationMapper.selectById(reservationId);
        if (rsv == null || !QuotaReservation.RESERVED.equals(rsv.getRsvStatus())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (rsv.getExpireTime().isAfter(now)) {
            return false;
        }
        int rows = reservationMapper.update(null, new LambdaUpdateWrapper<QuotaReservation>()
                .eq(QuotaReservation::getId, rsv.getId())
                .eq(QuotaReservation::getRsvStatus, QuotaReservation.RESERVED)
                .set(QuotaReservation::getRsvStatus, QuotaReservation.EXPIRED)
                .set(QuotaReservation::getReleasedTime, now)
                .set(QuotaReservation::getUpdatedBy, RequestContext.operatorId())
                .set(QuotaReservation::getUpdatedTime, now)
                .set(QuotaReservation::getRequestNo, RequestContext.requestNo())
                .set(QuotaReservation::getBizTime, now)
                .setSql("version_no = version_no + 1"));
        if (rows == 0) {
            return false; // 并发下已被其他路径结算
        }
        moveReserved(accountOf(rsv), rsv.getAmount(), false);
        QuotaAccount account = accountOf(rsv);
        writeLedger(account, rsv, QuotaLedger.EXPIRE, now, "占用超时自动归还");
        log.info("额度超时自动归还 store={} 单号={} 金额={}", rsv.getStoreCode(),
                rsv.getReservationNo(), rsv.getAmount());
        return true;
    }

    // ---------------- 查询 ----------------

    public QuotaReservation getReservation(String reservationNo) {
        return requireReservation(reservationNo);
    }

    /** 明细流水：门店必填，业务日可空（空=全部日期），按时间升序便于逐笔对账 */
    public List<QuotaLedger> listLedger(String storeCode, LocalDate bizDate) {
        LambdaQueryWrapper<QuotaLedger> qw = new LambdaQueryWrapper<QuotaLedger>()
                .eq(QuotaLedger::getStoreCode, storeCode)
                .orderByAsc(QuotaLedger::getId);
        if (bizDate != null) {
            qw.eq(QuotaLedger::getBizDate, bizDate);
        }
        return ledgerMapper.selectList(qw);
    }

    /**
     * 日结对平（按门店 + 业务日）：
     * 1) 当日预占合计 = 当日实扣+释放+过期合计 + 期末未结余额（与当日预占至今未结单据合计一致）；
     * 2) 账户预占余额 = 全量未结预占单据合计；
     * 3) 账户实扣累计 = 全量实扣流水合计。
     */
    public ReconcileReport reconcile(String storeCode, LocalDate bizDate) {
        QuotaAccount account = requireAccount(storeCode);
        LocalDate date = bizDate != null ? bizDate : LocalDate.now();

        ReconcileReport report = new ReconcileReport();
        report.setStoreCode(account.getStoreCode());
        report.setBizDate(date);

        List<QuotaLedger> dayEntries = ledgerMapper.selectList(new LambdaQueryWrapper<QuotaLedger>()
                .eq(QuotaLedger::getStoreCode, account.getStoreCode())
                .eq(QuotaLedger::getBizDate, date));
        BigDecimal reserveSum = BigDecimal.ZERO;
        BigDecimal confirmSum = BigDecimal.ZERO;
        BigDecimal releaseSum = BigDecimal.ZERO;
        BigDecimal expireSum = BigDecimal.ZERO;
        List<String> dayReserveNos = dayEntries.stream()
                .filter(e -> QuotaLedger.RESERVE.equals(e.getEntryType()))
                .map(QuotaLedger::getReservationNo)
                .collect(Collectors.toList());
        for (QuotaLedger entry : dayEntries) {
            switch (entry.getEntryType()) {
                case QuotaLedger.RESERVE: reserveSum = reserveSum.add(entry.getAmount()); break;
                case QuotaLedger.CONFIRM: confirmSum = confirmSum.add(entry.getAmount()); break;
                case QuotaLedger.RELEASE: releaseSum = releaseSum.add(entry.getAmount()); break;
                case QuotaLedger.EXPIRE: expireSum = expireSum.add(entry.getAmount()); break;
                default: break;
            }
        }
        BigDecimal openSum = reserveSum.subtract(confirmSum).subtract(releaseSum).subtract(expireSum);
        report.setReserveSum(reserveSum);
        report.setConfirmSum(confirmSum);
        report.setReleaseSum(releaseSum);
        report.setExpireSum(expireSum);
        report.setOpenSum(openSum);

        // 当日预占的单据中至今仍未结的实际合计
        BigDecimal dayOpenActual = BigDecimal.ZERO;
        if (!dayReserveNos.isEmpty()) {
            List<QuotaReservation> dayOpen = reservationMapper.selectList(
                    new LambdaQueryWrapper<QuotaReservation>()
                            .in(QuotaReservation::getReservationNo, dayReserveNos)
                            .eq(QuotaReservation::getRsvStatus, QuotaReservation.RESERVED));
            for (QuotaReservation r : dayOpen) {
                dayOpenActual = dayOpenActual.add(r.getAmount());
            }
        }
        report.setDayOpenActual(dayOpenActual);

        // 账户级对平：预占余额 vs 全量未结单据；实扣累计 vs 全量实扣流水
        BigDecimal openTotal = sumReservationOpen(account.getStoreCode());
        BigDecimal confirmTotal = sumLedger(account.getStoreCode(), QuotaLedger.CONFIRM);
        report.setAccountReserved(account.getReservedAmount());
        report.setAccountUsed(account.getUsedAmount());
        report.setOpenTotal(openTotal);
        report.setConfirmTotal(confirmTotal);

        if (openSum.compareTo(dayOpenActual) != 0) {
            report.getMismatches().add("当日预占未结余额[" + openSum + "]与当日预占至今未结单据合计["
                    + dayOpenActual + "]不平");
        }
        if (account.getReservedAmount().compareTo(openTotal) != 0) {
            report.getMismatches().add("账户预占余额[" + account.getReservedAmount()
                    + "]与全量未结预占单据合计[" + openTotal + "]不平");
        }
        if (account.getUsedAmount().compareTo(confirmTotal) != 0) {
            report.getMismatches().add("账户实扣累计[" + account.getUsedAmount()
                    + "]与全量实扣流水合计[" + confirmTotal + "]不平");
        }
        report.setBalanced(report.getMismatches().isEmpty());
        return report;
    }

    // ---------------- 内部 ----------------

    /** 账户预占余额变动：toUsed=true 预占转实扣（实扣累计同增）；false 归还（释放/超时还）。守卫：预占余额不为负 */
    private void moveReserved(QuotaAccount account, BigDecimal amount, boolean toUsed) {
        String delta = toUsed
                ? "reserved_amount = reserved_amount - {0}, used_amount = used_amount + {0}, version_no = version_no + 1"
                : "reserved_amount = reserved_amount - {0}, version_no = version_no + 1";
        int rows = accountMapper.update(null, new LambdaUpdateWrapper<QuotaAccount>()
                .eq(QuotaAccount::getStoreCode, account.getStoreCode())
                .set(QuotaAccount::getUpdatedBy, RequestContext.operatorId())
                .set(QuotaAccount::getUpdatedTime, LocalDateTime.now())
                .set(QuotaAccount::getRequestNo, RequestContext.requestNo())
                .set(QuotaAccount::getBizTime, RequestContext.bizTime())
                .setSql(delta, amount)
                .apply("reserved_amount >= {0}", amount));
        if (rows == 0) {
            // 预占单与账户同事务变动，正常不会到此；到此即数据异常，必须暴露
            throw new BizException(BizCode.SYSTEM_ERROR,
                    "账户预占余额不足，无法结算预占单[" + account.getStoreCode() + "]");
        }
    }

    /** 写明细流水（只追加）：动作后账户快照随流水落库，逐笔可对 */
    private void writeLedger(QuotaAccount accountAfter, QuotaReservation rsv,
                             String entryType, LocalDateTime bizTime, String remark) {
        QuotaLedger ledger = new QuotaLedger();
        ledger.setLedgerNo(newNo("LED"));
        ledger.setStoreCode(rsv.getStoreCode());
        ledger.setReservationNo(rsv.getReservationNo());
        ledger.setEntryType(entryType);
        ledger.setAmount(rsv.getAmount());
        ledger.setBizDate(bizTime.toLocalDate());
        ledger.setReservedAfter(accountAfter.getReservedAmount());
        ledger.setUsedAfter(accountAfter.getUsedAmount());
        ledger.setBizRef(rsv.getBizRef());
        ledger.setOperRemark(remark);
        ledgerMapper.insert(ledger);
    }

    private QuotaAccount requireAccount(String storeCode) {
        if (storeCode == null || storeCode.trim().isEmpty()) {
            throw new BizException(BizCode.VALIDATION_FAILED, "门店编码不能为空");
        }
        QuotaAccount account = accountMapper.selectOne(new LambdaQueryWrapper<QuotaAccount>()
                .eq(QuotaAccount::getStoreCode, storeCode.trim()));
        if (account == null) {
            throw new BizException(BizCode.QUOTA_ACCOUNT_NOT_FOUND);
        }
        return account;
    }

    private QuotaReservation requireReservation(String reservationNo) {
        QuotaReservation rsv = reservationMapper.selectOne(new LambdaQueryWrapper<QuotaReservation>()
                .eq(QuotaReservation::getReservationNo, reservationNo == null ? null : reservationNo.trim()));
        if (rsv == null) {
            throw new BizException(BizCode.QUOTA_RESERVATION_NOT_FOUND);
        }
        return rsv;
    }

    /** 仅 RESERVED 可结算；已终态的按状态给明确业务码 */
    private void requireSettleable(QuotaReservation rsv) {
        if (QuotaReservation.RESERVED.equals(rsv.getRsvStatus())) {
            return;
        }
        if (QuotaReservation.EXPIRED.equals(rsv.getRsvStatus())) {
            throw new BizException(BizCode.QUOTA_RESERVATION_EXPIRED);
        }
        throw new BizException(BizCode.QUOTA_RESERVATION_STATUS_ILLEGAL,
                QuotaReservation.CONFIRMED.equals(rsv.getRsvStatus()) ? "预占单已实扣" : "预占单已释放");
    }

    private QuotaAccount accountOf(QuotaReservation rsv) {
        return requireAccount(rsv.getStoreCode());
    }

    private BigDecimal sumReservationOpen(String storeCode) {
        Object sum = reservationMapper.selectObjs(new QueryWrapper<QuotaReservation>()
                .select("COALESCE(SUM(amount), 0)")
                .eq("store_code", storeCode)
                .eq("rsv_status", QuotaReservation.RESERVED)).get(0);
        return new BigDecimal(sum.toString());
    }

    private BigDecimal sumLedger(String storeCode, String entryType) {
        Object sum = ledgerMapper.selectObjs(new QueryWrapper<QuotaLedger>()
                .select("COALESCE(SUM(amount), 0)")
                .eq("store_code", storeCode)
                .eq("entry_type", entryType)).get(0);
        return new BigDecimal(sum.toString());
    }

    /** 单号：前缀-日期-随机段；全库唯一由 uk 约束兜底 */
    private String newNo(String prefix) {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String rand = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        return prefix + "-" + day + "-" + rand;
    }

    private static String trimToNull(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        return text.trim();
    }
}
