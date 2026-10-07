package com.bakery.oven.service;

import com.bakery.oven.common.BizException;
import com.bakery.oven.common.Dict;
import com.bakery.oven.entity.BakeOrder;
import com.bakery.oven.entity.BreadRecipe;
import com.bakery.oven.entity.Oven;
import com.bakery.oven.repository.BakeOrderRepository;
import com.bakery.oven.repository.BreadRecipeRepository;
import com.bakery.oven.repository.OvenRepository;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class BakeOrderService {

    private final BakeOrderRepository orderRepository;
    private final OvenRepository ovenRepository;
    private final BreadRecipeRepository recipeRepository;

    public BakeOrderService(BakeOrderRepository orderRepository,
                            OvenRepository ovenRepository,
                            BreadRecipeRepository recipeRepository) {
        this.orderRepository = orderRepository;
        this.ovenRepository = ovenRepository;
        this.recipeRepository = recipeRepository;
    }

    public List<BakeOrder> list(String status) {
        if (status != null && !status.isBlank()) {
            return orderRepository.findByStatus(status);
        }
        return orderRepository.findAll();
    }

    public BakeOrder create(BakeOrder input) {
        if (input.ovenId == null) {
            throw new BizException("请选择烤箱");
        }
        Oven oven = ovenRepository.findById(input.ovenId)
                .orElseThrow(() -> new BizException("烤箱不存在：" + input.ovenId));
        if ("故障".equals(oven.status)) {
            throw new BizException("故障烤箱不能排产：" + oven.code);
        }
        if (input.recipeId == null) {
            throw new BizException("请选择面包配方");
        }
        BreadRecipe recipe = recipeRepository.findById(input.recipeId)
                .orElseThrow(() -> new BizException("配方不存在：" + input.recipeId));
        if (!"上架".equals(recipe.status)) {
            throw new BizException("只有上架的配方才能排产：" + recipe.name);
        }
        if (input.trays == null || input.trays <= 0) {
            throw new BizException("实际烤盘数必须大于 0");
        }
        if (input.trays > oven.capacity) {
            throw new BizException("实际烤盘数不能超过烤箱容量（" + oven.capacity + "）：" + oven.code);
        }
        BakeOrder order = new BakeOrder();
        order.ovenId = input.ovenId;
        order.recipeId = input.recipeId;
        order.trays = input.trays;
        order.startTime = null;
        order.status = "待烤";
        order.createdAt = LocalDateTime.now();
        return orderRepository.save(order);
    }

    public BakeOrder update(Long id, BakeOrder input) {
        BakeOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new BizException("工单不存在：" + id));
        if (input.ovenId != null && !input.ovenId.equals(order.ovenId)) {
            Oven oven = ovenRepository.findById(input.ovenId)
                    .orElseThrow(() -> new BizException("烤箱不存在：" + input.ovenId));
            if ("故障".equals(oven.status)) {
                throw new BizException("故障烤箱不能排产：" + oven.code);
            }
            if (input.trays != null && input.trays > oven.capacity) {
                throw new BizException("实际烤盘数不能超过烤箱容量（" + oven.capacity + "）：" + oven.code);
            }
            // 编辑改挂也不得制造“一炉两张烘焙中”（故障接管走专门接口）
            if ("烘焙中".equals(order.status)) {
                boolean slotTaken = orderRepository.findByOvenIdAndStatus(input.ovenId, "烘焙中").stream()
                        .anyMatch(o -> !o.id.equals(order.id));
                if (slotTaken) {
                    throw new BizException("同一烤箱同时只能有一张「烘焙中」工单：" + oven.code);
                }
            }
            order.ovenId = input.ovenId;
        }
        if (input.recipeId != null && !input.recipeId.equals(order.recipeId)) {
            BreadRecipe recipe = recipeRepository.findById(input.recipeId)
                    .orElseThrow(() -> new BizException("配方不存在：" + input.recipeId));
            if (!"上架".equals(recipe.status)) {
                throw new BizException("只有上架的配方才能排产：" + recipe.name);
            }
            order.recipeId = input.recipeId;
        }
        if (input.trays != null) {
            if (input.trays <= 0) {
                throw new BizException("实际烤盘数必须大于 0");
            }
            Long ovenId = order.ovenId;
            if (ovenId != null) {
                Oven oven = ovenRepository.findById(ovenId).orElse(null);
                if (oven != null && input.trays > oven.capacity) {
                    throw new BizException("实际烤盘数不能超过烤箱容量（" + oven.capacity + "）：" + oven.code);
                }
            }
            order.trays = input.trays;
        }
        return orderRepository.save(order);
    }

    /** 待烤 → 烘焙中 */
    @org.springframework.transaction.annotation.Transactional
    public BakeOrder start(Long id) {
        // 先锁炉再锁单：与故障接管的加锁顺序（炉 → 单）保持一致，避免交叉死锁，
        // 同时用炉行锁串行化“一炉一张烘焙中工单”的占用裁决。
        BakeOrder first = orderRepository.findById(id)
                .orElseThrow(() -> new BizException("工单不存在：" + id));
        if (!Dict.canTransferBakeOrder(first.status, "烘焙中")) {
            throw new BizException("只有「待烤」的工单才能开始烘焙，当前为：" + first.status);
        }
        Oven oven = ovenRepository.findByIdForUpdate(first.ovenId)
                .orElseThrow(() -> new BizException("烤箱不存在：" + first.ovenId));
        BakeOrder order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("工单不存在：" + id));
        if (!"待烤".equals(order.status)) {
            throw new BizException("只有「待烤」的工单才能开始烘焙，当前为：" + order.status);
        }
        if ("故障".equals(oven.status)) {
            throw new BizException("故障烤箱上的工单不能开始烘焙：" + oven.code);
        }
        boolean conflict = orderRepository.findByOvenIdAndStatus(oven.id, "烘焙中").stream()
                .anyMatch(o -> !o.id.equals(id));
        if (conflict) {
            throw new BizException("同一烤箱同时只能有一张「烘焙中」工单：" + oven.code);
        }
        order.status = "烘焙中";
        order.startTime = LocalDateTime.now();
        oven.status = "占用";
        ovenRepository.save(oven);
        return orderRepository.save(order);
    }

    /** 烘焙中 → 已出炉 */
    @org.springframework.transaction.annotation.Transactional
    public BakeOrder done(Long id) {
        // 同样先锁炉再锁单，避免与故障接管交叉加锁导致死锁
        BakeOrder preview = orderRepository.findById(id)
                .orElseThrow(() -> new BizException("工单不存在：" + id));
        Oven oven = preview.ovenId == null ? null
                : ovenRepository.findByIdForUpdate(preview.ovenId).orElse(null);
        BakeOrder order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("工单不存在：" + id));
        if (!Dict.canTransferBakeOrder(order.status, "已出炉")) {
            throw new BizException("只有「烘焙中」的工单才能出炉，当前为：" + order.status);
        }
        order.status = "已出炉";
        if (oven != null && "占用".equals(oven.status)) {
            oven.status = "空闲";
            ovenRepository.save(oven);
        }
        return orderRepository.save(order);
    }

    /** 已出炉 → 已质检 */
    public BakeOrder inspect(Long id) {
        BakeOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new BizException("工单不存在：" + id));
        if (!Dict.canTransferBakeOrder(order.status, "已质检")) {
            throw new BizException("只有「已出炉」的工单才能质检，当前为：" + order.status);
        }
        order.status = "已质检";
        return orderRepository.save(order);
    }
}
