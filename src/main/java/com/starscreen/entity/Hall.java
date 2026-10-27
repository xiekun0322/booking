package com.starscreen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Data;

/**
 * 影厅实体，映射表 hall。
 *
 * <p><b>NOTE</b>：{@code rows / cols} 是 MySQL 保留字，列名必须加反引号。
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

    /** 影厅名，如 "1号厅"。 */
    @Column(length = 50, nullable = false)
    private String name;

    /** 影厅类型："IMAX厅" / "杜比全景声厅" / "RealD厅"。 */
    @Column(length = 50)
    private String type;

    /** 座位排数（保留字 rows）。 */
    @Column(name = "`rows`")
    private Integer rows;

    /** 座位列数（保留字 cols）。 */
    @Column(name = "`cols`")
    private Integer cols;
}
