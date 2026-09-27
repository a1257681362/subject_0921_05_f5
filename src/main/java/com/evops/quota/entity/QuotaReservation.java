package com.evops.quota.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 额度预占单（三段式）：
 * RESERVED 占用中 ──confirm──▶ CONFIRMED 已实扣（终态）
 *      ├──release──▶ RELEASED 已释放（终态，手工触发）
 *      └──expire───▶ EXPIRED 超时已还（终态，占用超过 40 分钟由定时任务自动归还）
 * 实扣必须以有效预占为前提；占用有效期 expire_time = 预占业务时间 + 40 分钟。
 */
@Getter
@Setter
@TableName("t_quota_reservation")
public class QuotaReservation extends BaseEntity {

    public static final String RESERVED = "RESERVED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String RELEASED = "RELEASED";
    public static final String EXPIRED = "EXPIRED";

    /** 预占单号，全库唯一 */
    private String reservationNo;
    private String storeCode;
    /** 预占金额 */
    private BigDecimal amount;
    /** 关联业务单号（如加工单号），可空 */
    private String bizRef;
    /** RESERVED / CONFIRMED / RELEASED / EXPIRED */
    private String rsvStatus;
    /** 占用有效期：预占业务时间 + 40 分钟，超时自动归还 */
    private LocalDateTime expireTime;
    private LocalDateTime confirmedTime;
    private LocalDateTime releasedTime;
}
