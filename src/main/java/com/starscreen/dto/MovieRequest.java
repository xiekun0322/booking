package com.starscreen.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MovieRequest {
    
    // 只有这里定义的字段，前端传了才会被接收！没定义的直接丢弃，彻底阻断参数注入
    
    @NotBlank(message = "电影名称不能为空")
    private String title;

    @NotBlank(message = "电影海报不能为空")
    private String poster;

    @NotNull(message = "电影时长不能为空")
    private Integer duration;

    private Double score;
    private String tag;
    private String releaseDate;
    private String status;
    private String director;
    private String actors;
    private String plot;
    private String genres;
}
