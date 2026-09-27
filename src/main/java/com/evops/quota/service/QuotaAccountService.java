package com.evops.quota.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.BizCode;
import com.evops.common.BizException;
import com.evops.common.LogMasks;
import com.evops.common.RequestContext;
import com.evops.quota.dto.QuotaAccountSaveRequest;
import com.evops.quota.dto.QuotaAdjustRequest;
import com.evops.quota.entity.QuotaAccount;
import com.evops.quota.entity.QuotaFlow;
import com.evops.quota.mapper.QuotaAccountMapper;
import com.evops.quota.mapper.QuotaFlowMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 门店额度账户：开户 / 调整总额度 / 查询（含可用额度）/ 流水检索。
 * 每门店一户（uk_quota_account_store 兜底并发重复开户）；余额变动走条件更新，
 * 全部变动写只追加流水。禁止物理删除。
 */
@Service
public class QuotaAccountService {

    private static final Logger log = LoggerFactory.getLogger(QuotaAccountService.class);

    @Resource
    private QuotaAccountMapper accountMapper;
    @Resource
    private QuotaFlowMapper flowMapper;
    @Resource
    private QuotaFlowWriter flowWriter;

    /** 开户：每门店一户，初始占用/实扣为 0，开户写一笔 ADJUST 流水锚定初始额度 */
    @Transactional(rollbackFor = Exception.class)
    public QuotaAccount create(QuotaAccountSaveRequest req) {
        String storeCode = req.getStoreCode().trim();
        // 业务层先拦一道，uk_quota_account_store 兜底并发
        Long exists = accountMapper.selectCount(new LambdaQueryWrapper<QuotaAccount>()
                .eq(QuotaAccount::getStoreCode, storeCode));
        if (exists != null && exists > 0) {
            throw new BizException(BizCode.QUOTA_ACCOUNT_DUPLICATED, "门店[" + storeCode + "]已开户");
        }
        QuotaAccount account = new QuotaAccount();
        account.setStoreCode(storeCode);
        account.setTotalQuota(req.getTotalQuota());
        account.setReservedAmount(BigDecimal.ZERO);
        account.setUsedAmount(BigDecimal.ZERO);
        account.setRemark(req.getRemark());
        accountMapper.insert(account);
        account = accountMapper.selectById(account.getId());
        flowWriter.write(account, null, QuotaFlow.ADJUST, account.getTotalQuota(), "开户初始额度");
        log.info("额度账户开户 store={} 总额度={}", storeCode, account.getTotalQuota());
        return withAvailable(account);
    }

    /** 调整总额度：不得低于当前 占用+实扣（条件更新守护，并发占用变动同样被拦） */
    @Transactional(rollbackFor = Exception.class)
    public QuotaAccount adjust(Long id, QuotaAdjustRequest req) {
        QuotaAccount account = requireAccount(id);
        BigDecimal delta = req.getTotalQuota().subtract(account.getTotalQuota());
        int rows = accountMapper.update(null, balanceUpdate(id)
                .apply("reserved_amount + used_amount <= {0}", req.getTotalQuota())
                .set(QuotaAccount::getTotalQuota, req.getTotalQuota())
                .setSql("version_no = version_no + 1"));
        if (rows == 0) {
            throw new BizException(BizCode.QUOTA_ADJUST_BELOW_OCCUPIED,
                    "门店[" + account.getStoreCode() + "]当前占用加已实扣为["
                            + account.getReservedAmount().add(account.getUsedAmount()) + "]");
        }
        account = accountMapper.selectById(id);
        flowWriter.write(account, null, QuotaFlow.ADJUST, delta,
                req.getRemark() == null ? "调整总额度" : LogMasks.singleLine(req.getRemark()));
        log.info("额度账户调额 store={} 总额度={} 差额={}", account.getStoreCode(), account.getTotalQuota(), delta);
        return withAvailable(account);
    }

    public QuotaAccount get(Long id) {
        return withAvailable(requireAccount(id));
    }

    public List<QuotaAccount> list(String storeCode) {
        LambdaQueryWrapper<QuotaAccount> qw = new LambdaQueryWrapper<>();
        if (storeCode != null && !storeCode.trim().isEmpty()) {
            qw.eq(QuotaAccount::getStoreCode, storeCode.trim());
        }
        qw.orderByAsc(QuotaAccount::getStoreCode);
        return accountMapper.selectList(qw).stream().map(this::withAvailable).collect(Collectors.toList());
    }

    /** 额度流水分页检索：对账查询入口 */
    public Page<QuotaFlow> pageFlows(long current, long size, String storeCode, String holdNo, String flowType) {
        LambdaQueryWrapper<QuotaFlow> qw = new LambdaQueryWrapper<>();
        if (storeCode != null && !storeCode.trim().isEmpty()) {
            qw.eq(QuotaFlow::getStoreCode, storeCode.trim());
        }
        if (holdNo != null && !holdNo.trim().isEmpty()) {
            qw.eq(QuotaFlow::getHoldNo, holdNo.trim());
        }
        if (flowType != null && !flowType.trim().isEmpty()) {
            qw.eq(QuotaFlow::getFlowType, flowType.trim().toUpperCase());
        }
        qw.orderByDesc(QuotaFlow::getId);
        return flowMapper.selectPage(new Page<>(current, size), qw);
    }

    public QuotaAccount requireAccount(Long id) {
        QuotaAccount account = accountMapper.selectById(id);
        if (account == null) {
            throw new BizException(BizCode.QUOTA_ACCOUNT_NOT_FOUND);
        }
        return account;
    }

    public QuotaAccount requireAccountByStore(String storeCode) {
        QuotaAccount account = accountMapper.selectOne(new LambdaQueryWrapper<QuotaAccount>()
                .eq(QuotaAccount::getStoreCode, storeCode));
        if (account == null) {
            throw new BizException(BizCode.QUOTA_ACCOUNT_NOT_FOUND, "门店[" + storeCode + "]未开户");
        }
        return account;
    }

    private QuotaAccount withAvailable(QuotaAccount account) {
        account.setAvailableAmount(account.getTotalQuota()
                .subtract(account.getReservedAmount())
                .subtract(account.getUsedAmount()));
        return account;
    }

    /**
     * 账户余额条件更新公共部分：审计字段显式写（条件更新不经 MetaObjectHandler）。
     * 调用方追加守护条件（apply）与余额变动（setSql，金额服务端校验过，非外部文本）。
     */
    static LambdaUpdateWrapper<QuotaAccount> balanceUpdate(Long accountId) {
        return new LambdaUpdateWrapper<QuotaAccount>()
                .eq(QuotaAccount::getId, accountId)
                .set(QuotaAccount::getUpdatedBy, RequestContext.operatorId())
                .set(QuotaAccount::getUpdatedTime, LocalDateTime.now())
                .set(QuotaAccount::getRequestNo, RequestContext.requestNo())
                .set(QuotaAccount::getBizTime, RequestContext.bizTime());
    }
}
