package com.bakery.oven.service;

import com.bakery.oven.common.BizException;
import com.bakery.oven.common.Dict;
import com.bakery.oven.entity.BreadRecipe;
import com.bakery.oven.repository.BreadRecipeRepository;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class BreadRecipeService {

    private final BreadRecipeRepository recipeRepository;

    public BreadRecipeService(BreadRecipeRepository recipeRepository) {
        this.recipeRepository = recipeRepository;
    }

    public List<BreadRecipe> list(String status) {
        if (status != null && !status.isBlank()) {
            return recipeRepository.findByStatus(status);
        }
        return recipeRepository.findAll();
    }

    public BreadRecipe create(BreadRecipe input) {
        if (input.name == null || input.name.isBlank()) {
            throw new BizException("面包名不能为空");
        }
        if (recipeRepository.findByName(input.name).isPresent()) {
            throw new BizException("面包名已存在：" + input.name);
        }
        if (input.needTrays == null || input.needTrays <= 0) {
            throw new BizException("需用烤盘数必须大于 0");
        }
        if (input.bakeMinutes == null || input.bakeMinutes <= 0) {
            throw new BizException("烘焙时长必须大于 0");
        }
        BreadRecipe recipe = new BreadRecipe();
        recipe.name = input.name;
        recipe.needTrays = input.needTrays;
        recipe.bakeMinutes = input.bakeMinutes;
        recipe.status = (input.status != null && Dict.isValidRecipeStatus(input.status)) ? input.status : "上架";
        recipe.createdAt = LocalDateTime.now();
        return recipeRepository.save(recipe);
    }

    public BreadRecipe update(Long id, BreadRecipe input) {
        BreadRecipe recipe = recipeRepository.findById(id)
                .orElseThrow(() -> new BizException("配方不存在：" + id));
        if (input.name != null) {
            if (input.name.isBlank()) {
                throw new BizException("面包名不能为空");
            }
            recipeRepository.findByName(input.name).ifPresent(r -> {
                if (!r.id.equals(id)) {
                    throw new BizException("面包名已存在：" + input.name);
                }
            });
            recipe.name = input.name;
        }
        if (input.needTrays != null) {
            if (input.needTrays <= 0) {
                throw new BizException("需用烤盘数必须大于 0");
            }
            recipe.needTrays = input.needTrays;
        }
        if (input.bakeMinutes != null) {
            if (input.bakeMinutes <= 0) {
                throw new BizException("烘焙时长必须大于 0");
            }
            recipe.bakeMinutes = input.bakeMinutes;
        }
        if (input.status != null && !input.status.isBlank()) {
            if (!Dict.isValidRecipeStatus(input.status)) {
                throw new BizException("配方状态非法：" + input.status);
            }
            recipe.status = input.status;
        }
        return recipeRepository.save(recipe);
    }
}
