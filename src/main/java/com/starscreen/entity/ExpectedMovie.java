package com.starscreen.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Data;

/** 最受期待，映射表 expected_movie。 */
@Data
@Entity
@Table(name = "expected_movie")
public class ExpectedMovie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 排名 1~3。 */
    private Integer rankNum;

    private String  movieTitle;

    /** 想看人数。 */
    private Integer wantCount;
}
