package com.evops.quota.maintenance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.RequestContext;
import com.evops.quota.entity.QuotaHold;
import com.evops.quota.entity.QuotaRecon;
import com.evops.quota.mapper.QuotaHoldMapper;
import com.evops.quota.service.QuotaHoldService;
import com.evops.quota.service.QuotaReconService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 额度后台维护：
 * 1) 超时预占扫描（默认每 60s）：到期预占逐笔独立事务归还，单笔失败不影响其他，
 *    重启期间漏扫的下一圈自然补上；
 * 2) 每日日结对平（默认 03:47，避开整点与 03:17 任务清理）：逐账户核对写 t_quota_recon。
 * 后台写入的审计挂 EXPIRE:&lt;holdNo&gt; / QUOTA-RECON 请求号，可追溯。
 */
@Component
public class QuotaMaintenance {

    private static final Logger log = LoggerFactory.getLogger(QuotaMaintenance.class);

    @Resource
    private QuotaHoldMapper holdMapper;
    @Resource
    private QuotaHoldService holdService;
    @Resource
    private QuotaReconService reconService;

    @Scheduled(fixedDelayString = "${evops.quota.expire-scan-ms:60000}",
            initialDelayString = "${evops.quota.expire-scan-ms:60000}")
    public void scheduledExpireScan() {
        int returned = expireOverdue();
        if (returned > 0) {
            log.info("额度超时预占归还：本次 {} 笔", returned);
        }
    }

    /** 归还全部已到期预占，返回归还笔数（供定时任务与测试直调） */
    public int expireOverdue() {
        List<QuotaHold> overdue = holdMapper.selectList(new LambdaQueryWrapper<QuotaHold>()
                .eq(QuotaHold::getHoldStatus, QuotaHold.HELD)
                .le(QuotaHold::getExpireTime, LocalDateTime.now())
                .orderByAsc(QuotaHold::getId));
        int returned = 0;
        for (QuotaHold hold : overdue) {
            // 审计挂 EXPIRE:<holdNo>：流水可追溯；逐单唯一，不顶预占单 request_no 唯一约束
            RequestContext.set(0L, "EXPIRE:" + hold.getHoldNo(), null);
            try {
                if (holdService.expireOne(hold.getId())) {
                    returned++;
                }
            } catch (Exception ex) {
                log.error("额度超时归还失败 holdNo={}", hold.getHoldNo(), ex);
            } finally {
                RequestContext.clear();
            }
        }
        return returned;
    }

    /** 每日日结对平：逐账户核对占用/实扣与流水一致，报告落 t_quota_recon */
    @Scheduled(cron = "${evops.quota.recon-cron:0 47 3 * * ?}")
    public void scheduledRecon() {
        RequestContext.set(0L, "QUOTA-RECON", null);
        try {
            List<QuotaRecon> results = reconService.reconcileToday(null);
            long mismatch = results.stream()
                    .filter(r -> QuotaRecon.MISMATCH.equals(r.getReconResult())).count();
            log.info("额度日结对平完成：账户 {} 户，不符 {} 户", results.size(), mismatch);
        } finally {
            RequestContext.clear();
        }
    }
}
