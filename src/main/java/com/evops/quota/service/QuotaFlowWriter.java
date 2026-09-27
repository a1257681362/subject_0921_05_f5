package com.evops.quota.service;

import com.evops.quota.entity.QuotaAccount;
import com.evops.quota.entity.QuotaFlow;
import com.evops.quota.entity.QuotaHold;
import com.evops.quota.mapper.QuotaFlowMapper;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.math.BigDecimal;

/**
 * 额度流水写入器：账户余额每次变动写一行（只追加），带变动后三项余额快照。
 * 审计字段（操作人/请求号/业务时间）由 MetaObjectHandler 自动填充。
 */
@Component
class QuotaFlowWriter {

    @Resource
    private QuotaFlowMapper flowMapper;

    /**
     * @param accountAfter 变动后的账户快照（同事务内重查得到）
     * @param hold         关联预占单（开户/调额为 null）
     * @param flowType     RESERVE/CONFIRM/RELEASE/EXPIRE/ADJUST
     * @param amount       变动金额（ADJUST 为总额度差额，可负；其余为正）
     */
    void write(QuotaAccount accountAfter, QuotaHold hold, String flowType, BigDecimal amount, String remark) {
        QuotaFlow flow = new QuotaFlow();
        flow.setAccountId(accountAfter.getId());
        flow.setStoreCode(accountAfter.getStoreCode());
        flow.setHoldId(hold == null ? null : hold.getId());
        flow.setHoldNo(hold == null ? null : hold.getHoldNo());
        flow.setFlowType(flowType);
        flow.setAmount(amount);
        flow.setTotalAfter(accountAfter.getTotalQuota());
        flow.setReservedAfter(accountAfter.getReservedAmount());
        flow.setUsedAfter(accountAfter.getUsedAmount());
        flow.setOperRemark(remark);
        flowMapper.insert(flow);
    }
}
