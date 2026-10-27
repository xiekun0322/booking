package com.starscreen.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.ToString;

/**
 * 用户实体，映射表 t_user。
 *
 * <p><b>安全</b>：{@code password} 加了 {@code @ToString.Exclude} 与 {@code @JsonIgnore}，
 * 避免日志打印与 JSON 序列化时泄露。
 *
 * <p><b>NOTE</b>：多处业务代码使用 {@code !user.getDeleted()}，
 * 若历史数据 deleted 为 null 会 NPE，建议统一用 {@code Boolean.FALSE.equals(...)}。
 */
@Data
@Entity
@Table(name = "t_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 50, nullable = false)
    private String username;

    /** BCrypt 加密后的密码；手机号注册用户为空字符串。 */
    @Column(length = 100)
    @ToString.Exclude
    @JsonIgnore
    private String password;

    @Column(unique = true, length = 20)
    private String phone;

    @Column(length = 30)
    private String createTime;

    /** USER / ADMIN。 */
    @Column(length = 20)
    private String role;

    /** 软删除：false=正常，true=已注销。 */
    @Column(nullable = false)
    private Boolean deleted = false;
}
