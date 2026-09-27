package com.evops.quota.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 额度预占单。状态机：HELD 占用中 → CONFIRMED 已实扣 / RELEASED 已手工释放 / EXPIRED 超时归还。
 * 状态流转走条件更新（仅 HELD 可流转），手工操作与定时扫描互踢只有一方成功。
 */
@Getter
@Setter
@TableName("t_quota_hold")
public class QuotaHold extends BaseEntity {

    public static final String HELD = "HELD";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String RELEASED = "RELEASED";
    public static final String EXPIRED = "EXPIRED";

    /** 预占单号，服务端生成，全库唯一 */
    private String holdNo;
    private Long accountId;
    /** 冗余门店编码，便于检索 */
    private String storeCode;
    private BigDecimal amount;
    /** HELD / CONFIRMED / RELEASED / EXPIRED */
    private String holdStatus;
    /** 关联业务类型（如 PROCESS_ORDER），可空 */
    private String bizType;
    /** 关联业务单号（如加工单号），可空 */
    private String bizNo;
    /** 占用截止时间（预占起 40 分钟，服务器时钟） */
    private LocalDateTime expireTime;
    private LocalDateTime confirmedTime;
    private LocalDateTime releasedTime;
    /** 手工释放备注 / 超时自动归还 */
    private String releaseReason;
    private String remark;
}
