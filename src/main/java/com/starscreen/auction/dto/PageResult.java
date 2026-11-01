package com.starscreen.auction.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分页结果。
 *
 * <p>不直接返回 Spring 的 {@code Page}：它的 JSON 结构随版本变化（{@code content} /
 * {@code pageable} / {@code totalElements} 嵌套很深），前端要跟着框架版本走。
 * 这里只给四个必要字段，接口契约稳定。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> content;

    /** 页码，从 1 开始（对前端友好；Spring 内部是 0 基，转换在本类完成）。 */
    private int page;

    private int size;

    private long total;

    private int totalPages;

    public static <E, T> PageResult<T> of(Page<E> source, List<T> content) {
        return new PageResult<>(content,
                source.getNumber() + 1,
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages());
    }
}
