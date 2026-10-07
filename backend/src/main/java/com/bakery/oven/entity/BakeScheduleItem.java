package com.bakery.oven.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 排产版本明细：保存版本时的计算结果快照（建议烤箱、预计时间、风险、原因）。
 * 均为建议信息，工单的实际归属与状态以 bake_order 为准，不被本表影响。
 */
@Entity
@Table(name = "bake_schedule_item")
public class BakeScheduleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 排产版本 id */
    @Column(name = "schedule_id", nullable = false)
    public Long scheduleId;

    /** 工单 id */
    @Column(name = "order_id", nullable = false)
    public Long orderId;

    /** 优先级序号（0 起，越小越先排） */
    @Column(name = "seq", nullable = false)
    public Integer seq;

    /** 是否排入（false=未排入区，reason 给出原因） */
    @Column(name = "scheduled", nullable = false)
    public Boolean scheduled;

    /** 建议占用烤箱 id（仅建议，不改写工单归属） */
    @Column(name = "oven_id")
    public Long ovenId;

    /** 建议占用烤箱炉号（快照） */
    @Column(name = "oven_code", length = 32)
    public String ovenCode;

    /** 预计开始时间 */
    @Column(name = "est_start")
    public LocalDateTime estStart;

    /** 预计完成时间 */
    @Column(name = "est_finish")
    public LocalDateTime estFinish;

    /** 截止时间风险：正常 / 紧张 / 无截止时间 */
    @Column(name = "risk", length = 16)
    public String risk;

    /** 排入解释或未排入原因（容量预警 / 预计逾期等） */
    @Column(name = "reason")
    public String reason;
}
