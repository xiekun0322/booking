package com.starscreen.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 后台新增/编辑电影的请求体。
 *
 * <p>只定义白名单字段——前端多传的键会被 Jackson 忽略，
 * 从结构上阻断参数注入。
 */
@Data
public class MovieRequest {

    @NotBlank(message = "电影名称不能为空")
    private String title;

    @NotBlank(message = "电影海报不能为空")
    private String poster;

    @NotNull(message = "电影时长不能为空")
    private Integer duration;

    private Double  score;
    private String  tag;
    private String  releaseDate;
    private String  status;
    private String  director;
    private String  actors;
    private String  plot;
    private String  genres;
}
