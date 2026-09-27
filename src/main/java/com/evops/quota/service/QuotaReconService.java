package com.evops.quota.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.quota.entity.QuotaAccount;
import com.evops.quota.entity.QuotaFlow;
import com.evops.quota.entity.QuotaHold;
import com.evops.quota.entity.QuotaRecon;
import com.evops.quota.mapper.QuotaAccountMapper;
import com.evops.quota.mapper.QuotaFlowMapper;
import com.evops.quota.mapper.QuotaHoldMapper;
import com.evops.quota.mapper.QuotaReconMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 额度日结对平：每日定时（也可手工触发）逐账户核对——
 * 账户占用 = 未结预占合计；账户实扣 = 实扣流水合计；每笔实扣必有预占来源；
 * 无超时未还预占；占用+实扣不超总额。全对平 BALANCED，任一不符 MISMATCH 并附差异明细。
 * 报告每账户每日一行（uk_quota_recon），重跑覆盖原行。
 */
@Service
public class QuotaReconService {

    private static final Logger log = LoggerFactory.getLogger(QuotaReconService.class);

    /** 超时未还容忍窗口：扫描间隔内的归还延迟不算 dangling */
    private static final int DANGLING_GRACE_MINUTES = 2;

    @Resource
    private QuotaAccountMapper accountMapper;
    @Resource
    private QuotaHoldMapper holdMapper;
    @Resource
    private QuotaFlowMapper flowMapper;
    @Resource
    private QuotaReconMapper reconMapper;
    @Resource
    private ObjectMapper objectMapper;
    /** 自调用走代理：逐账户独立事务，单账户失败不影响其他账户 */
    @Resource
    private ObjectProvider<QuotaReconService> selfProvider;

    /** 对全部（或指定）门店账户做当日日结对平，返回各账户报告 */
    public List<QuotaRecon> reconcileToday(String storeCode) {
        LambdaQueryWrapper<QuotaAccount> qw = new LambdaQueryWrapper<>();
        if (storeCode != null && !storeCode.trim().isEmpty()) {
            qw.eq(QuotaAccount::getStoreCode, storeCode.trim());
        }
        qw.orderByAsc(QuotaAccount::getStoreCode);
        List<QuotaAccount> accounts = accountMapper.selectList(qw);
        List<QuotaRecon> results = new ArrayList<>();
        for (QuotaAccount account : accounts) {
            try {
                results.add(selfProvider.getObject().reconcileOneInNewTx(account.getId(), LocalDate.now()));
            } catch (Exception ex) {
                // 单账户对平失败不阻断其他账户；异常留堆栈，凭 traceId 定位
                log.error("额度日结对平异常 store={}", account.getStoreCode(), ex);
            }
        }
        return results;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public QuotaRecon reconcileOneInNewTx(Long accountId, LocalDate reconDate) {
        QuotaAccount account = accountMapper.selectById(accountId);

        // 应有占用 = 未结预占（HELD）合计
        BigDecimal reservedExpected = sumHeldAmount(accountId);
        // 应有实扣 = 实扣流水（CONFIRM）合计
        BigDecimal usedExpected = sumFlowAmount(accountId, QuotaFlow.CONFIRM);
        // 超时未还预占（容忍扫描延迟后仍存在）
        List<QuotaHold> dangling = holdMapper.selectList(new LambdaQueryWrapper<QuotaHold>()
                .eq(QuotaHold::getAccountId, accountId)
                .eq(QuotaHold::getHoldStatus, QuotaHold.HELD)
                .lt(QuotaHold::getExpireTime, LocalDateTime.now().minusMinutes(DANGLING_GRACE_MINUTES)));
        // 无预占来源的实扣流水（理论恒 0：防御性核对"实扣以预占为前提"）
        Long confirmWithoutReserve = flowMapper.selectCount(new QueryWrapper<QuotaFlow>()
                .eq("account_id", accountId)
                .eq("flow_type", QuotaFlow.CONFIRM)
                .apply("NOT EXISTS (SELECT 1 FROM t_quota_flow r "
                        + "WHERE r.hold_id = t_quota_flow.hold_id AND r.flow_type = '" + QuotaFlow.RESERVE + "')"));
        boolean quotaExceeded = account.getReservedAmount().add(account.getUsedAmount())
                .compareTo(account.getTotalQuota()) > 0;

        int orphanConfirms = confirmWithoutReserve == null ? 0 : confirmWithoutReserve.intValue();
        boolean balanced = reservedExpected.compareTo(account.getReservedAmount()) == 0
                && usedExpected.compareTo(account.getUsedAmount()) == 0
                && dangling.isEmpty()
                && orphanConfirms == 0
                && !quotaExceeded;

        // 每账户每日一行：重跑覆盖原行（运维报告数据，业务流水仍只追加）
        QuotaRecon recon = reconMapper.selectOne(new LambdaQueryWrapper<QuotaRecon>()
                .eq(QuotaRecon::getAccountId, accountId)
                .eq(QuotaRecon::getReconDate, reconDate));
        if (recon == null) {
            recon = new QuotaRecon();
            recon.setReconDate(reconDate);
            recon.setAccountId(accountId);
            recon.setStoreCode(account.getStoreCode());
        }
        recon.setReservedExpected(reservedExpected);
        recon.setReservedActual(account.getReservedAmount());
        recon.setUsedExpected(usedExpected);
        recon.setUsedActual(account.getUsedAmount());
        recon.setDanglingHoldCount(dangling.size());
        recon.setConfirmWithoutReserve(orphanConfirms);
        recon.setReconResult(balanced ? QuotaRecon.BALANCED : QuotaRecon.MISMATCH);
        recon.setDetailJson(buildDetail(account, reservedExpected, usedExpected, dangling, quotaExceeded));
        if (recon.getId() == null) {
            reconMapper.insert(recon);
        } else {
            reconMapper.updateById(recon);
        }
        if (balanced) {
            log.info("额度日结对平 store={} 结果=BALANCED 占用={} 实扣={}",
                    account.getStoreCode(), account.getReservedAmount(), account.getUsedAmount());
        } else {
            log.warn("额度日结对平不符 store={} 应有占用={} 实有占用={} 应有实扣={} 实有实扣={} "
                            + "超时未还={} 无预占实扣={} 超总额={}",
                    account.getStoreCode(), reservedExpected, account.getReservedAmount(),
                    usedExpected, account.getUsedAmount(), dangling.size(), orphanConfirms, quotaExceeded);
        }
        return recon;
    }

    public List<QuotaRecon> list(String storeCode, LocalDate reconDate) {
        LambdaQueryWrapper<QuotaRecon> qw = new LambdaQueryWrapper<>();
        if (storeCode != null && !storeCode.trim().isEmpty()) {
            qw.eq(QuotaRecon::getStoreCode, storeCode.trim());
        }
        if (reconDate != null) {
            qw.eq(QuotaRecon::getReconDate, reconDate);
        }
        qw.orderByDesc(QuotaRecon::getReconDate).orderByAsc(QuotaRecon::getStoreCode);
        return reconMapper.selectList(qw);
    }

    /** 未结预占（HELD）金额合计：对平口径的"应有占用" */
    private BigDecimal sumHeldAmount(Long accountId) {
        List<Object> rows = holdMapper.selectObjs(new QueryWrapper<QuotaHold>()
                .select("COALESCE(SUM(amount),0)")
                .eq("account_id", accountId)
                .eq("hold_status", QuotaHold.HELD));
        return toAmount(rows);
    }

    /** 指定类型流水金额合计：对平口径的"应有实扣"（flowType=CONFIRM） */
    private BigDecimal sumFlowAmount(Long accountId, String flowType) {
        List<Object> rows = flowMapper.selectObjs(new QueryWrapper<QuotaFlow>()
                .select("COALESCE(SUM(amount),0)")
                .eq("account_id", accountId)
                .eq("flow_type", flowType));
        return toAmount(rows);
    }

    private BigDecimal toAmount(List<Object> rows) {
        if (rows == null || rows.isEmpty() || rows.get(0) == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(rows.get(0).toString());
    }

    private String buildDetail(QuotaAccount account, BigDecimal reservedExpected,
                               BigDecimal usedExpected, List<QuotaHold> dangling, boolean quotaExceeded) {
        ObjectNode detail = objectMapper.createObjectNode();
        ArrayNode danglingNos = detail.putArray("danglingHolds");
        dangling.forEach(h -> danglingNos.add(h.getHoldNo()));
        detail.put("reservedDiff", account.getReservedAmount().subtract(reservedExpected).toPlainString());
        detail.put("usedDiff", account.getUsedAmount().subtract(usedExpected).toPlainString());
        detail.put("quotaExceeded", quotaExceeded);
        return detail.toString();
    }
}
