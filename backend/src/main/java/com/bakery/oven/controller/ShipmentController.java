package com.bakery.oven.controller;

import com.bakery.oven.common.ApiResponse;
import com.bakery.oven.entity.Shipment;
import com.bakery.oven.service.ShipmentService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final ShipmentService shipmentService;

    public ShipmentController(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @GetMapping
    public ApiResponse<List<Shipment>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(shipmentService.list(status));
    }

    @PostMapping
    public ApiResponse<Shipment> create(@RequestBody Shipment input) {
        return ApiResponse.success(shipmentService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<Shipment> update(@PathVariable Long id, @RequestBody Shipment input) {
        return ApiResponse.success(shipmentService.update(id, input));
    }

    @PostMapping("/{id}/send")
    public ApiResponse<Shipment> send(@PathVariable Long id) {
        return ApiResponse.success(shipmentService.send(id));
    }
}
