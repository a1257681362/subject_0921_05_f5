package com.evops.quota.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BizCode;
import com.evops.common.BizException;
import com.evops.common.LogMasks;
import com.evops.common.RequestContext;
import com.evops.common.trace.TraceContext;
import com.evops.quota.dto.QuotaHoldActionRequest;
import com.evops.quota.dto.QuotaHoldCreateRequest;
import com.evops.quota.entity.QuotaAccount;
import com.evops.quota.entity.QuotaFlow;
import com.evops.quota.entity.QuotaHold;
import com.evops.quota.mapper.QuotaAccountMapper;
import com.evops.quota.mapper.QuotaHoldMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 额度三段式：预占（HELD）→ 确认实扣（CONFIRMED）/ 释放（RELEASED 手工、EXPIRED 超时）。
 * <p>
 * 规则：预占 40 分钟内有效（服务器时钟，evops.quota.hold-ttl-minutes 可配），超时由定时扫描
 * 自动归还；实扣必须以有效预占为前提且与预占等额；余额变动走条件更新（可用不足即 0 行），
 * 预占状态流转走 CAS（仅 HELD 可流转），手工操作与超时扫描互踢只有一方成功；
 * 每次变动写只追加流水，供对账与日结对平。
 */
@Service
public class QuotaHoldService {

    private static final Logger log = LoggerFactory.getLogger(QuotaHoldService.class);

    private static final DateTimeFormatter HOLD_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final char[] RANDOM_CHARS = "0123456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 预占占用时长（分钟）：超时自动归还 */
    @Value("${evops.quota.hold-ttl-minutes:40}")
    private int holdTtlMinutes;

    @Resource
    private QuotaAccountMapper accountMapper;
    @Resource
    private QuotaHoldMapper holdMapper;
    @Resource
    private QuotaAccountService accountService;
    @Resource
    private QuotaFlowWriter flowWriter;
    /** 自调用走代理：确认/释放撞到已超时预占时，内联结算须独立事务（外层回滚不影响归还） */
    @Resource
    private ObjectProvider<QuotaHoldService> selfProvider;

    // ---------------- 预占 ----------------

    @Transactional(rollbackFor = Exception.class)
    public QuotaHold reserve(QuotaHoldCreateRequest req) {
        String storeCode = req.getStoreCode().trim();
        String bizType = trimToNull(req.getBizType());
        String bizNo = trimToNull(req.getBizNo());
        // 关联加工单号入 MDC：本次预占的日志可与加工单日志按单号聚合检索
        if (bizNo != null && "PROCESS_ORDER".equalsIgnoreCase(bizType)) {
            TraceContext.putOrderNo(bizNo);
        }
        QuotaAccount account = accountService.requireAccountByStore(storeCode);

        // 重复提交业务层先拦一道（uk_quota_hold_request 库层兜底）
        String requestNo = RequestContext.requestNo();
        if (requestNo != null && !requestNo.trim().isEmpty()) {
            Long sameRequest = holdMapper.selectCount(new LambdaQueryWrapper<QuotaHold>()
                    .eq(QuotaHold::getRequestNo, requestNo));
            if (sameRequest != null && sameRequest > 0) {
                throw new BizException(BizCode.REQUEST_DUPLICATED);
            }
        }

        // 条件占用：可用额度（总额-占用-实扣）足够才扣占，0 行=额度不足，并发下不会超占
        int rows = accountMapper.update(null, QuotaAccountService.balanceUpdate(account.getId())
                .apply("reserved_amount + used_amount + {0} <= total_quota", req.getAmount())
                .setSql("reserved_amount = reserved_amount + " + req.getAmount()
                        + ", version_no = version_no + 1"));
        if (rows == 0) {
            throw new BizException(BizCode.QUOTA_INSUFFICIENT,
                    "门店[" + storeCode + "]可用额度不足，预占金额[" + req.getAmount() + "]");
        }

        QuotaHold hold = new QuotaHold();
        hold.setHoldNo(generateHoldNo());
        hold.setAccountId(account.getId());
        hold.setStoreCode(storeCode);
        hold.setAmount(req.getAmount());
        hold.setHoldStatus(QuotaHold.HELD);
        hold.setBizType(bizType);
        hold.setBizNo(bizNo);
        hold.setExpireTime(LocalDateTime.now().plusMinutes(holdTtlMinutes));
        hold.setRemark(req.getRemark());
        holdMapper.insert(hold);

        QuotaAccount after = accountMapper.selectById(account.getId());
        flowWriter.write(after, hold, QuotaFlow.RESERVE, req.getAmount(),
                req.getRemark() == null ? "预占占用" : LogMasks.singleLine(req.getRemark()));
        log.info("额度预占 store={} holdNo={} 金额={} 到期={}", storeCode, hold.getHoldNo(),
                req.getAmount(), hold.getExpireTime());
        return holdMapper.selectById(hold.getId());
    }

    // ---------------- 确认实扣（以预占为前提，等额） ----------------

    @Transactional(rollbackFor = Exception.class)
    public QuotaHold confirm(String holdNo, QuotaHoldActionRequest req) {
        QuotaHold hold = requireHold(holdNo);
        if (!QuotaHold.HELD.equals(hold.getHoldStatus())) {
            throw statusIllegal(hold);
        }
        if (isExpired(hold)) {
            // 已超时但扫描未及：先独立事务结算归还（本事务回滚不影响归还），再拒绝实扣
            selfProvider.getObject().expireOne(hold.getId());
            throw new BizException(BizCode.QUOTA_HOLD_STATUS_ILLEGAL, "预占已超时自动归还，不可实扣");
        }
        // CAS 流转：仅 HELD 可确认，与超时扫描互踢只有一方成功
        int rows = holdMapper.update(null, holdTransit(hold.getId())
                .set(QuotaHold::getHoldStatus, QuotaHold.CONFIRMED)
                .set(QuotaHold::getConfirmedTime, LocalDateTime.now()));
        if (rows == 0) {
            throw statusIllegal(holdMapper.selectById(hold.getId()));
        }
        // 占用转实扣（等额）：守护账户占用足够，不足说明数据异常，回滚暴露
        int acc = accountMapper.update(null, QuotaAccountService.balanceUpdate(hold.getAccountId())
                .apply("reserved_amount >= {0}", hold.getAmount())
                .setSql("reserved_amount = reserved_amount - " + hold.getAmount()
                        + ", used_amount = used_amount + " + hold.getAmount()
                        + ", version_no = version_no + 1"));
        if (acc == 0) {
            throw new BizException(BizCode.SYSTEM_ERROR, "账户占用余额不足，实扣失败");
        }
        QuotaAccount after = accountMapper.selectById(hold.getAccountId());
        String remark = req == null ? null : trimToNull(req.getRemark());
        flowWriter.write(after, hold, QuotaFlow.CONFIRM, hold.getAmount(),
                remark == null ? "确认实扣" : LogMasks.singleLine(remark));
        log.info("额度实扣 store={} holdNo={} 金额={}", hold.getStoreCode(), hold.getHoldNo(), hold.getAmount());
        return holdMapper.selectById(hold.getId());
    }

    // ---------------- 释放（手工触发；超时由扫描自动归还） ----------------

    @Transactional(rollbackFor = Exception.class)
    public QuotaHold release(String holdNo, QuotaHoldActionRequest req) {
        QuotaHold hold = requireHold(holdNo);
        if (!QuotaHold.HELD.equals(hold.getHoldStatus())) {
            throw statusIllegal(hold);
        }
        if (isExpired(hold)) {
            // 已超时：按超时归还结算，结果与手工释放一致（额度归还），直接返回最新状态
            selfProvider.getObject().expireOne(hold.getId());
            return holdMapper.selectById(hold.getId());
        }
        String remark = req == null ? null : trimToNull(req.getRemark());
        int rows = holdMapper.update(null, holdTransit(hold.getId())
                .set(QuotaHold::getHoldStatus, QuotaHold.RELEASED)
                .set(QuotaHold::getReleasedTime, LocalDateTime.now())
                .set(QuotaHold::getReleaseReason, remark == null ? "手工释放" : LogMasks.singleLine(remark)));
        if (rows == 0) {
            throw statusIllegal(holdMapper.selectById(hold.getId()));
        }
        releaseAccountReserved(hold);
        QuotaAccount after = accountMapper.selectById(hold.getAccountId());
        flowWriter.write(after, hold, QuotaFlow.RELEASE, hold.getAmount(),
                remark == null ? "手工释放" : LogMasks.singleLine(remark));
        log.info("额度释放 store={} holdNo={} 金额={}", hold.getStoreCode(), hold.getHoldNo(), hold.getAmount());
        return holdMapper.selectById(hold.getId());
    }

    /**
     * 超时归还：独立事务逐笔结算。定时扫描逐笔调用；确认/释放撞到已超时预占时内联调用。
     * CAS 兜底：已被确认/释放/归还的预占返回 false，不重复归还。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean expireOne(Long holdId) {
        int rows = holdMapper.update(null, holdTransit(holdId)
                .set(QuotaHold::getHoldStatus, QuotaHold.EXPIRED)
                .set(QuotaHold::getReleasedTime, LocalDateTime.now())
                .set(QuotaHold::getReleaseReason, "超时自动归还"));
        if (rows == 0) {
            return false;
        }
        QuotaHold hold = holdMapper.selectById(holdId);
        releaseAccountReserved(hold);
        QuotaAccount after = accountMapper.selectById(hold.getAccountId());
        flowWriter.write(after, hold, QuotaFlow.EXPIRE, hold.getAmount(), "预占超时自动归还");
        log.info("额度超时归还 store={} holdNo={} 金额={}", hold.getStoreCode(), hold.getHoldNo(), hold.getAmount());
        return true;
    }

    // ---------------- 查询 ----------------

    public QuotaHold getByHoldNo(String holdNo) {
        return requireHold(holdNo);
    }

    public List<QuotaHold> list(String storeCode, String status) {
        LambdaQueryWrapper<QuotaHold> qw = new LambdaQueryWrapper<>();
        if (storeCode != null && !storeCode.trim().isEmpty()) {
            qw.eq(QuotaHold::getStoreCode, storeCode.trim());
        }
        if (status != null && !status.trim().isEmpty()) {
            qw.eq(QuotaHold::getHoldStatus, status.trim().toUpperCase());
        }
        qw.orderByDesc(QuotaHold::getId);
        return holdMapper.selectList(qw);
    }

    // ---------------- 内部 ----------------

    private void releaseAccountReserved(QuotaHold hold) {
        int acc = accountMapper.update(null, QuotaAccountService.balanceUpdate(hold.getAccountId())
                .apply("reserved_amount >= {0}", hold.getAmount())
                .setSql("reserved_amount = reserved_amount - " + hold.getAmount()
                        + ", version_no = version_no + 1"));
        if (acc == 0) {
            throw new BizException(BizCode.SYSTEM_ERROR, "账户占用余额不足，归还失败");
        }
    }

    private QuotaHold requireHold(String holdNo) {
        if (holdNo == null || holdNo.trim().isEmpty()) {
            throw new BizException(BizCode.QUOTA_HOLD_NOT_FOUND);
        }
        QuotaHold hold = holdMapper.selectOne(new LambdaQueryWrapper<QuotaHold>()
                .eq(QuotaHold::getHoldNo, holdNo.trim()));
        if (hold == null) {
            throw new BizException(BizCode.QUOTA_HOLD_NOT_FOUND);
        }
        return hold;
    }

    private boolean isExpired(QuotaHold hold) {
        return hold.getExpireTime() != null && !hold.getExpireTime().isAfter(LocalDateTime.now());
    }

    private BizException statusIllegal(QuotaHold hold) {
        String desc;
        switch (hold.getHoldStatus()) {
            case QuotaHold.CONFIRMED:
                desc = "预占已实扣";
                break;
            case QuotaHold.RELEASED:
                desc = "预占已手工释放";
                break;
            case QuotaHold.EXPIRED:
                desc = "预占已超时归还";
                break;
            default:
                desc = "当前状态[" + hold.getHoldStatus() + "]";
                break;
        }
        return new BizException(BizCode.QUOTA_HOLD_STATUS_ILLEGAL, desc);
    }

    /** 预占状态 CAS 公共部分：仅 HELD 可流转；审计字段显式写（条件更新不经 MetaObjectHandler） */
    private LambdaUpdateWrapper<QuotaHold> holdTransit(Long holdId) {
        return new LambdaUpdateWrapper<QuotaHold>()
                .eq(QuotaHold::getId, holdId)
                .eq(QuotaHold::getHoldStatus, QuotaHold.HELD)
                .set(QuotaHold::getUpdatedBy, RequestContext.operatorId())
                .set(QuotaHold::getUpdatedTime, LocalDateTime.now())
                .set(QuotaHold::getRequestNo, RequestContext.requestNo())
                .set(QuotaHold::getBizTime, RequestContext.bizTime())
                .setSql("version_no = version_no + 1");
    }

    /** 预占单号：HOLD-时间-6 位随机；uk_quota_hold_no 兜底极端碰撞（重试即可） */
    private String generateHoldNo() {
        StringBuilder suffix = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            suffix.append(RANDOM_CHARS[RANDOM.nextInt(RANDOM_CHARS.length)]);
        }
        return "HOLD-" + LocalDateTime.now().format(HOLD_NO_TIME) + "-" + suffix;
    }

    private static String trimToNull(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        return text.trim();
    }
}
