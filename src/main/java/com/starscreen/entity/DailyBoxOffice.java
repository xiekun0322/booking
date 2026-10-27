package com.starscreen.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Data;

/** 今日票房，映射表 daily_box_office。 */
@Data
@Entity
@Table(name = "daily_box_office")
public class DailyBoxOffice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 排名 1~5。 */
    private Integer rankNum;

    private String  movieTitle;

    /** 票房（单位：万）。 */
    private Double  amount;
}
