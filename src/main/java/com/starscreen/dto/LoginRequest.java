package com.starscreen.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 账号密码登录请求。
 *
 * <p><b>安全提醒</b>：密码字段是 String，会短暂驻留内存。
 * 生产环境建议全链路 HTTPS、登录接口限流、失败次数锁定。
 */
@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
