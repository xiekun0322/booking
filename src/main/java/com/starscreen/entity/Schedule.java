package com.starscreen.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "schedule")
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "movie_id")
    private Long movieId;

    /** ★ 新增：影院 ID */
    @Column(name = "cinema_id")
    private Long cinemaId;

    /** ★ 新增：影厅 ID */
    @Column(name = "hall_id")
    private Long hallId;

    @Column(name = "cinema_name")
    private String cinemaName;

    @Column(name = "hall_name")
    private String hallName;

    @Column(name = "start_time")
    private String startTime;

    @Column(name = "end_time")
    private String endTime;

    private String language;

    private Double price;

    @Column(length = 20)
    private String date;
}
