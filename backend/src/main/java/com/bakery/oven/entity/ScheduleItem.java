package com.bakery.oven.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 排产明细：某个版本里一张待烤工单的建议结果。
 * placed=true  已排入：带顺序、建议烤箱、预计开始/完成、截止风险、容量说明与排入原因；
 * placed=false 未排入：带原因类型（无可用烤箱/容量不足/预计逾期）与说明，不伪装成已安排。
 */
@Entity
@Table(name = "schedule_item")
public class ScheduleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 所属排产版本 */
    @Column(name = "version_id", nullable = false)
    public Long versionId;

    /** 工单 id */
    @Column(name = "order_id", nullable = false)
    public Long orderId;

    /** 建议顺序（仅已排入） */
    @Column(name = "seq_no")
    public Integer seqNo;

    /** 1=已排入 0=未排入 */
    @Column(name = "placed", nullable = false)
    public Boolean placed;

    /** 建议占用烤箱（未排入为空） */
    @Column(name = "oven_id")
    public Long ovenId;

    /** 建议烤箱炉号（冗余，便于展示） */
    @Column(name = "oven_code", length = 32)
    public String ovenCode;

    /** 预计开始时间 */
    @Column(name = "est_start_time")
    public LocalDateTime estStartTime;

    /** 预计完成时间 */
    @Column(name = "est_finish_time")
    public LocalDateTime estFinishTime;

    /** 交付截止（保存时快照） */
    @Column(name = "due_time")
    public LocalDateTime dueTime;

    /** 截止风险：无 / 紧张 */
    @Column(name = "deadline_risk", length = 16)
    public String deadlineRisk;

    /** 未排入类型：NO_OVEN / CAPACITY / OVERDUE / RECIPE_MISSING */
    @Column(name = "reason_type", length = 16)
    public String reasonType;

    /** 容量说明 / 容量冲突提示 */
    @Column(name = "capacity_note", length = 128)
    public String capacityNote;

    /** 可解释原因：排入依据或未排入原因 */
    @Column(name = "reason", length = 255)
    public String reason;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
