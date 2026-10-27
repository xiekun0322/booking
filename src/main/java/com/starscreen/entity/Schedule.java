package com.starscreen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Data;

/** 场次实体，映射表 schedule。 */
@Data
@Entity
@Table(name = "schedule")
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "movie_id")
    private Long movieId;

    @Column(name = "cinema_id")
    private Long cinemaId;

    @Column(name = "hall_id")
    private Long hallId;

    @Column(name = "cinema_name")
    private String cinemaName;

    @Column(name = "hall_name")
    private String hallName;

    /** 格式 "HH:mm"，与 date 拼接解析为 LocalDateTime（见 OrderService）。 */
    @Column(name = "start_time")
    private String startTime;

    @Column(name = "end_time")
    private String endTime;

    private String language;

    private Double price;

    /** 格式 "yyyy-MM-dd"。 */
    @Column(length = 20)
    private String date;
}
