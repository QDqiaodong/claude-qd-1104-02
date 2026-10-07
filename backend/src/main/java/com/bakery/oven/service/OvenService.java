package com.bakery.oven.service;

import com.bakery.oven.common.BizException;
import com.bakery.oven.common.Dict;
import com.bakery.oven.entity.Oven;
import com.bakery.oven.repository.OvenRepository;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OvenService {

    private final OvenRepository ovenRepository;

    public OvenService(OvenRepository ovenRepository) {
        this.ovenRepository = ovenRepository;
    }

    public List<Oven> list(String status) {
        if (status != null && !status.isBlank()) {
            return ovenRepository.findByStatus(status);
        }
        return ovenRepository.findAll();
    }

    public Oven create(Oven input) {
        if (input.code == null || input.code.isBlank()) {
            throw new BizException("炉号不能为空");
        }
        if (ovenRepository.findByCode(input.code).isPresent()) {
            throw new BizException("炉号已存在：" + input.code);
        }
        if (input.capacity == null || input.capacity <= 0) {
            throw new BizException("烤箱容量必须大于 0");
        }
        Oven oven = new Oven();
        oven.code = input.code;
        oven.capacity = input.capacity;
        oven.status = (input.status != null && Dict.isValidOvenStatus(input.status)) ? input.status : "空闲";
        oven.createdAt = LocalDateTime.now();
        return ovenRepository.save(oven);
    }

    public Oven update(Long id, Oven input) {
        Oven oven = ovenRepository.findById(id)
                .orElseThrow(() -> new BizException("烤箱不存在：" + id));
        if (input.code != null) {
            if (input.code.isBlank()) {
                throw new BizException("炉号不能为空");
            }
            ovenRepository.findByCode(input.code).ifPresent(o -> {
                if (!o.id.equals(id)) {
                    throw new BizException("炉号已存在：" + input.code);
                }
            });
            oven.code = input.code;
        }
        if (input.capacity != null) {
            if (input.capacity <= 0) {
                throw new BizException("烤箱容量必须大于 0");
            }
            oven.capacity = input.capacity;
        }
        if (input.status != null && !input.status.isBlank()) {
            if (!Dict.isValidOvenStatus(input.status)) {
                throw new BizException("烤箱状态非法：" + input.status);
            }
            oven.status = input.status;
        }
        return ovenRepository.save(oven);
    }

    /** 置为故障 */
    public Oven breakOven(Long id) {
        Oven oven = ovenRepository.findById(id)
                .orElseThrow(() -> new BizException("烤箱不存在：" + id));
        oven.status = "故障";
        return ovenRepository.save(oven);
    }

    /** 修复为空闲 */
    public Oven repairOven(Long id) {
        Oven oven = ovenRepository.findById(id)
                .orElseThrow(() -> new BizException("烤箱不存在：" + id));
        oven.status = "空闲";
        return ovenRepository.save(oven);
    }
}
