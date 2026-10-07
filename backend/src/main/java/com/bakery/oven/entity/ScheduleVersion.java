package com.bakery.oven.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 排产版本：一次保存产生的建议顺序快照。
 * input_fingerprint / input_snapshot 记录保存当时的排产输入（烤箱、待烤与烘焙中工单、配方），
 * 之后任何输入变化都会让旧版本被标记为「已过期」，但版本内容本身不被改写。
 */
@Entity
@Table(name = "schedule_version")
public class ScheduleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 版本号，单调递增，全库唯一 */
    @Column(name = "version_no", nullable = false, unique = true)
    public Integer versionNo;

    /** 保存时基于的版本号（乐观并发校验）；首个版本为 null */
    @Column(name = "base_version_no")
    public Integer baseVersionNo;

    /** 操作员 */
    @Column(name = "operator_name", length = 32)
    public String operatorName;

    /** 排产输入指纹（SHA-256）：烤箱状态/容量、待烤与烘焙中工单、配方耗时的快照哈希 */
    @Column(name = "input_fingerprint", nullable = false, length = 64)
    public String inputFingerprint;

    /** 排产输入快照（JSON），用于过期时逐条列出变化原因 */
    @Column(name = "input_snapshot")
    public String inputSnapshot;

    /** 排入工单数 */
    @Column(name = "scheduled_count", nullable = false)
    public Integer scheduledCount;

    /** 未排入工单数 */
    @Column(name = "unscheduled_count", nullable = false)
    public Integer unscheduledCount;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
