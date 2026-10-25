package com.starscreen.controller;

import java.util.List;
import java.util.stream.Stream;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.starscreen.common.Result;
import com.starscreen.entity.Cinema;
import com.starscreen.repository.CinemaRepository;

import lombok.RequiredArgsConstructor;

/**
 * 【功能】影院公开 API。
 *         为 /cinemas 页面提供影院列表（带筛选）。
 */
@RestController
@RequestMapping("/api/cinemas")
@RequiredArgsConstructor
public class CinemaController {

    private final CinemaRepository cinemaRepository;

    /**
     * 【功能】查询影院列表（带筛选）。
     * 【调用链】all-cinemas.html: fetch('/api/cinemas?brand=xxx&district=xxx')
     * 【参数】全部可选，null 表示不过滤
     */
    @GetMapping
    public Result<List<Cinema>> list(
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) String hall) {

        List<Cinema> all = cinemaRepository.findAll();

        List<Cinema> filtered = all.stream()
                .filter(c -> brand == null || brand.isEmpty() || brand.equals(c.getBrand()))
                .filter(c -> district == null || district.isEmpty() || district.equals(c.getDistrict()))
                .toList();

        return Result.success(filtered);
    }

    /**
     * 【功能】查询单个影院。
     */
    @GetMapping("/{id}")
    public Result<Cinema> detail(@PathVariable Long id) {
        return Result.success(cinemaRepository.findById(id).orElse(null));
    }
}
