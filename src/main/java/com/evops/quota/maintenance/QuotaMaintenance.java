package com.evops.quota.maintenance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.trace.TraceContext;
import com.evops.quota.entity.QuotaReservation;
import com.evops.quota.mapper.QuotaReservationMapper;
import com.evops.quota.service.QuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 预占超时自动归还：
 * 1) 定时扫描（默认每分钟）将过期 RESERVED 预占单置 EXPIRED 并归还账户预占额度，逐单独立事务；
 * 2) 启动补扫一次：服务停机期间过期的预占，重启后立即归还，不等下一个扫描周期。
 */
@Component
public class QuotaMaintenance implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QuotaMaintenance.class);

    /** 单批扫描上限：防止积压时单轮跑太久，剩余的下个周期继续 */
    private static final int BATCH_SIZE = 200;

    @Resource
    private QuotaReservationMapper reservationMapper;
    @Resource
    private QuotaService quotaService;

    /** 每分钟扫描过期预占（cron 可调，避开整点整分） */
    @Scheduled(cron = "${evops.quota.expire-scan-cron:17 * * * * ?}")
    public void scheduledSweep() {
        int expired = sweepExpired();
        if (expired > 0) {
            log.info("预占超时扫描：归还 {} 笔", expired);
        }
    }

    /** 启动补扫：停机期间过期的预占立即归还 */
    @Override
    public void run(ApplicationArguments args) {
        TraceContext.ensureTrace();
        try {
            int expired = sweepExpired();
            if (expired > 0) {
                log.info("启动补扫：归还停机期间过期预占 {} 笔", expired);
            }
        } finally {
            TraceContext.clear();
        }
    }

    /** 扫描并归还全部过期预占（分批），返回归还笔数 */
    public int sweepExpired() {
        int total = 0;
        List<QuotaReservation> overdue;
        do {
            overdue = reservationMapper.selectList(new LambdaQueryWrapper<QuotaReservation>()
                    .eq(QuotaReservation::getRsvStatus, QuotaReservation.RESERVED)
                    .le(QuotaReservation::getExpireTime, LocalDateTime.now())
                    .orderByAsc(QuotaReservation::getId)
                    .last("LIMIT " + BATCH_SIZE));
            for (QuotaReservation rsv : overdue) {
                try {
                    if (quotaService.expireOneInNewTx(rsv.getId())) {
                        total++;
                    }
                } catch (Exception ex) {
                    // 单笔失败独立事务已回滚：记日志继续其他单，下个周期重试
                    log.warn("预占超时归还失败 reservationNo={}", rsv.getReservationNo(), ex);
                }
            }
        } while (overdue.size() >= BATCH_SIZE);
        return total;
    }
}
