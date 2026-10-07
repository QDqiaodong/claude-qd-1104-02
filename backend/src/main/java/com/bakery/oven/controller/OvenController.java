package com.bakery.oven.controller;

import com.bakery.oven.common.ApiResponse;
import com.bakery.oven.entity.Oven;
import com.bakery.oven.service.OvenService;

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
@RequestMapping("/api/ovens")
public class OvenController {

    private final OvenService ovenService;

    public OvenController(OvenService ovenService) {
        this.ovenService = ovenService;
    }

    @GetMapping
    public ApiResponse<List<Oven>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(ovenService.list(status));
    }

    @PostMapping
    public ApiResponse<Oven> create(@RequestBody Oven input) {
        return ApiResponse.success(ovenService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<Oven> update(@PathVariable Long id, @RequestBody Oven input) {
        return ApiResponse.success(ovenService.update(id, input));
    }

    @PostMapping("/{id}/break")
    public ApiResponse<Oven> breakOven(@PathVariable Long id) {
        return ApiResponse.success(ovenService.breakOven(id));
    }

    @PostMapping("/{id}/repair")
    public ApiResponse<Oven> repairOven(@PathVariable Long id) {
        return ApiResponse.success(ovenService.repairOven(id));
    }
}
