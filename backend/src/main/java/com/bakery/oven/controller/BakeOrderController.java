package com.bakery.oven.controller;

import com.bakery.oven.common.ApiResponse;
import com.bakery.oven.entity.BakeOrder;
import com.bakery.oven.service.BakeOrderService;

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
@RequestMapping("/api/bake-orders")
public class BakeOrderController {

    private final BakeOrderService orderService;

    public BakeOrderController(BakeOrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public ApiResponse<List<BakeOrder>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(orderService.list(status));
    }

    @PostMapping
    public ApiResponse<BakeOrder> create(@RequestBody BakeOrder input) {
        return ApiResponse.success(orderService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<BakeOrder> update(@PathVariable Long id, @RequestBody BakeOrder input) {
        return ApiResponse.success(orderService.update(id, input));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<BakeOrder> start(@PathVariable Long id) {
        return ApiResponse.success(orderService.start(id));
    }

    @PostMapping("/{id}/done")
    public ApiResponse<BakeOrder> done(@PathVariable Long id) {
        return ApiResponse.success(orderService.done(id));
    }

    @PostMapping("/{id}/inspect")
    public ApiResponse<BakeOrder> inspect(@PathVariable Long id) {
        return ApiResponse.success(orderService.inspect(id));
    }
}
