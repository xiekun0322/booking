package com.starscreen.entity;

import lombok.ToString;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
    @ToString.Exclude // 【核心修复】：告诉 Lombok，生成 toString() 时把我踢出去！
    @JsonIgnore       // 【双保险】：告诉 Jackson，转成 JSON 返回给前端时，也把我忽略掉！
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