package com.bakery.oven.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "bake_order")
public class BakeOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "oven_id")
    public Long ovenId;

    @Column(name = "recipe_id")
    public Long recipeId;

    /** 实际烤盘数 */
    @Column(name = "trays", nullable = false)
    public Integer trays;

    /** 开始时间（可空） */
    @Column(name = "start_time")
    public LocalDateTime startTime;

    /** 交付截止时间（可空，排产逾期评估用） */
    @Column(name = "deadline")
    public LocalDateTime deadline;

    /** 待烤 / 烘焙中 / 已出炉 / 已质检 */
    @Column(name = "status", nullable = false, length = 16)
    public String status;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
