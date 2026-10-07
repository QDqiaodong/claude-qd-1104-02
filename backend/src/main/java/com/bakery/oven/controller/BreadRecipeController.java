package com.bakery.oven.controller;

import com.bakery.oven.common.ApiResponse;
import com.bakery.oven.entity.BreadRecipe;
import com.bakery.oven.service.BreadRecipeService;

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
@RequestMapping("/api/bread-recipes")
public class BreadRecipeController {

    private final BreadRecipeService recipeService;

    public BreadRecipeController(BreadRecipeService recipeService) {
        this.recipeService = recipeService;
    }

    @GetMapping
    public ApiResponse<List<BreadRecipe>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(recipeService.list(status));
    }

    @PostMapping
    public ApiResponse<BreadRecipe> create(@RequestBody BreadRecipe input) {
        return ApiResponse.success(recipeService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<BreadRecipe> update(@PathVariable Long id, @RequestBody BreadRecipe input) {
        return ApiResponse.success(recipeService.update(id, input));
    }
}
