package com.starscreen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Data;

/** 影院实体，映射表 cinema。对应 /cinemas 影院列表页。 */
@Data
@Entity
@Table(name = "cinema")
public class Cinema {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 影院名。 */
    @Column(length = 100, nullable = false)
    private String name;

    /** 品牌：万达/金逸/横店/CGV/万象/其他。 */
    @Column(length = 50)
    private String brand;

    /** 行政区：集美区/思明区/湖里区... */
    @Column(length = 50)
    private String district;

    @Column(length = 200)
    private String address;

    /** 服务标签（逗号分隔，如 "退,改签,折扣卡"）。 */
    @Column(length = 200)
    private String tags;

    /** 距离（km，演示用硬编码）。 */
    private Double distance;

    @Column(length = 20)
    private String phone;

    @Column(length = 30)
    private String createTime;
}
