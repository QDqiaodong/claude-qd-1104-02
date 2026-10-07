package com.bakery.oven.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "shipment")
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "bake_order_id")
    public Long bakeOrderId;

    /** 数量 */
    @Column(name = "qty", nullable = false)
    public Integer qty;

    /** 门店 / 外卖 */
    @Column(name = "channel", nullable = false, length = 16)
    public String channel;

    /** 待发 / 已发 */
    @Column(name = "status", nullable = false, length = 16)
    public String status;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
