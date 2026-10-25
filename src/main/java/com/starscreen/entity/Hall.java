package com.starscreen.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 【功能】影厅实体。
 *         影院下的放映厅，含座位布局。
 *
 * 【注意】rows / cols 是 MySQL 保留字，需加反引号。
 */
@Data
@Entity
@Table(name = "hall")
public class Hall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cinema_id")
    private Long cinemaId;

    /** 影厅名（"1号厅"） */
    @Column(length = 50, nullable = false)
    private String name;

    /** 影厅类型（"IMAX厅"/"杜比全景声厅"/"RealD厅"） */
    @Column(length = 50)
    private String type;

    /** ★ 座位排数（rows 是保留字） */
    @Column(name = "`rows`")
    private Integer rows;

    /** ★ 座位列数 */
    @Column(name = "`cols`")
    private Integer cols;
}
