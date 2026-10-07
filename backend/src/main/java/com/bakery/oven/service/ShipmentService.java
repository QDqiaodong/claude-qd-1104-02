package com.bakery.oven.service;

import com.bakery.oven.common.BizException;
import com.bakery.oven.common.Dict;
import com.bakery.oven.entity.BakeOrder;
import com.bakery.oven.entity.Shipment;
import com.bakery.oven.repository.BakeOrderRepository;
import com.bakery.oven.repository.ShipmentRepository;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;
    private final BakeOrderRepository orderRepository;

    public ShipmentService(ShipmentRepository shipmentRepository, BakeOrderRepository orderRepository) {
        this.shipmentRepository = shipmentRepository;
        this.orderRepository = orderRepository;
    }

    public List<Shipment> list(String status) {
        if (status != null && !status.isBlank()) {
            return shipmentRepository.findByStatus(status);
        }
        return shipmentRepository.findAll();
    }

    public Shipment create(Shipment input) {
        if (input.bakeOrderId == null) {
            throw new BizException("请选择要出货的工单");
        }
        BakeOrder order = orderRepository.findById(input.bakeOrderId)
                .orElseThrow(() -> new BizException("工单不存在：" + input.bakeOrderId));
        if (!"已质检".equals(order.status)) {
            throw new BizException("只能出货「已质检」的工单，当前为：" + order.status);
        }
        if (input.qty == null || input.qty <= 0) {
            throw new BizException("出货数量必须大于 0");
        }
        if (input.channel == null || !Dict.isValidShipmentChannel(input.channel)) {
            throw new BizException("出货渠道必须是「门店」或「外卖」");
        }
        Shipment shipment = new Shipment();
        shipment.bakeOrderId = input.bakeOrderId;
        shipment.qty = input.qty;
        shipment.channel = input.channel;
        shipment.status = "待发";
        shipment.createdAt = LocalDateTime.now();
        return shipmentRepository.save(shipment);
    }

    public Shipment update(Long id, Shipment input) {
        Shipment shipment = shipmentRepository.findById(id)
                .orElseThrow(() -> new BizException("出货单不存在：" + id));
        if (input.qty != null) {
            if (input.qty <= 0) {
                throw new BizException("出货数量必须大于 0");
            }
            shipment.qty = input.qty;
        }
        if (input.channel != null && !input.channel.isBlank()) {
            if (!Dict.isValidShipmentChannel(input.channel)) {
                throw new BizException("出货渠道必须是「门店」或「外卖」");
            }
            shipment.channel = input.channel;
        }
        return shipmentRepository.save(shipment);
    }

    /** 待发 → 已发 */
    public Shipment send(Long id) {
        Shipment shipment = shipmentRepository.findById(id)
                .orElseThrow(() -> new BizException("出货单不存在：" + id));
        if (!"待发".equals(shipment.status)) {
            throw new BizException("只有「待发」的出货单才能发货，当前为：" + shipment.status);
        }
        shipment.status = "已发";
        return shipmentRepository.save(shipment);
    }
}
