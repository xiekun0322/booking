package com.starscreen.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "movie")
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 100)
    private String title;

    @Column(length = 500)
    private String poster;

    private Double score;

    @Column(length = 50)
    private String tag;

    @Column(length = 50)
    private String releaseDate;

    @Column(length = 20)
    private String status;

    private Integer wantCount;

    @Column(length = 100)
    private String director;

    @Column(length = 500)
    private String actors;

    private Integer duration;

    /** ★ 新增：剧情简介 */
    @Column(columnDefinition = "TEXT")
    private String plot;

    /** ★ 新增：类型（逗号分隔：剧情,动作,科幻） */
    @Column(length = 200)
    private String genres;

    @Column(length = 30)
    private String createTime;
}
