package com.bakery.oven.repository;

import com.bakery.oven.entity.BreadRecipe;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BreadRecipeRepository extends JpaRepository<BreadRecipe, Long> {

    Optional<BreadRecipe> findByName(String name);

    List<BreadRecipe> findByStatus(String status);
}
