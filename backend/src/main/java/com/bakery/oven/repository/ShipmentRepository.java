package com.bakery.oven.repository;

import com.bakery.oven.entity.Shipment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    List<Shipment> findByStatus(String status);

    List<Shipment> findByBakeOrderId(Long bakeOrderId);
}
