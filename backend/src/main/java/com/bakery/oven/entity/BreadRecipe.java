package com.bakery.oven.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "bread_recipe")
public class BreadRecipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 面包名 */
    @Column(name = "name", nullable = false, length = 64, unique = true)
    public String name;

    /** 需用烤盘数 */
    @Column(name = "need_trays", nullable = false)
    public Integer needTrays;

    /** 烘焙时长（分钟） */
    @Column(name = "bake_minutes", nullable = false)
    public Integer bakeMinutes;

    /** 上架 / 隐藏 */
    @Column(name = "status", nullable = false, length = 16)
    public String status;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
