package com.starscreen.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Data;

/** TOP 100 榜单，映射表 top100_movie。 */
@Data
@Entity
@Table(name = "top100_movie")
public class Top100Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer rankNum;

    private String  movieTitle;

    private Double  score;
}
