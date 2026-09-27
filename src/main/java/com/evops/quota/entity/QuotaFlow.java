package com.evops.quota.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 额度流水（只追加）：每次账户余额变动一行，带变动后三项余额快照。
 * 是对账查询与每日日结对平的依据；禁止更新与删除。
 */
@Getter
@Setter
@TableName("t_quota_flow")
public class QuotaFlow extends BaseEntity {

    /** 预占占用 */
    public static final String RESERVE = "RESERVE";
    /** 确认实扣（以预占为前提，等额） */
    public static final String CONFIRM = "CONFIRM";
    /** 手工释放 */
    public static final String RELEASE = "RELEASE";
    /** 超时自动归还 */
    public static final String EXPIRE = "EXPIRE";
    /** 开户/调整总额度 */
    public static final String ADJUST = "ADJUST";

    private Long accountId;
    private String storeCode;
    /** 关联预占单（开户/调额流水为空） */
    private Long holdId;
    private String holdNo;
    /** RESERVE / CONFIRM / RELEASE / EXPIRE / ADJUST */
    private String flowType;
    /** 变动金额（ADJUST 为总额度差额，可负；其余为正） */
    private BigDecimal amount;
    /** 变动后总额度 */
    private BigDecimal totalAfter;
    /** 变动后占用中 */
    private BigDecimal reservedAfter;
    /** 变动后已实扣 */
    private BigDecimal usedAfter;
    private String operRemark;
}
