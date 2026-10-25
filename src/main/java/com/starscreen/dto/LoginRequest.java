package com.starscreen.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 【功能】登录请求体。
 *         前端 login.html 提交的 JSON：
 *         { "username": "alice", "password": "123456" }
 *
 * 【调用方】
 *          - UserController.login(@RequestBody @Valid LoginRequest, HttpSession)
 *          - UserService.login(LoginRequest)
 *
 * 【被调用】
 *          - Jackson 反序列化
 *          - Hibernate Validator 校验非空
 *          - UserServiceTest.testLogin_Success / testLogin_WrongPassword
 *
 * 【安全提醒】
 *          密码字段是 String，会短暂驻留内存。
 *          生产环境建议：
 *          - 全程 HTTPS
 *          - 登录接口限流（防撞库）
 *          - 失败次数过多锁定账号
 */
@Data
public class LoginRequest {

    /** 用户名（必填） */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 密码（必填） */
    @NotBlank(message = "密码不能为空")
    private String password;
}
