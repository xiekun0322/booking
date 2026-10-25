package com.starscreen.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "t_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 50, nullable = false)
    private String username;

    /** BCrypt 加密后的密码；手机号注册的用户此字段为空字符串 */
    @Column(length = 100)
    private String password;

    /** 手机号（唯一），首次注册的唯一入口 */
    @Column(unique = true, length = 20)
    private String phone;

    @Column(length = 30)
    private String createTime;

    /** ★ 角色：USER / ADMIN */
    @Column(length = 20)
    private String role;
    
    /** ★ 软删除标记：0=正常，1=已注销 */
    @Column(nullable = false)
    private Boolean deleted = false;
}