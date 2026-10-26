package com.starscreen.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 绑定账号密码请求。已用手机号登录的用户首次绑定用户名+密码。 */
@Data
public class BindAccountRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 20, message = "用户名长度 2~20 位")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 20, message = "密码长度 8~20 位")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*._-])[A-Za-z\\d!@#$%^&*._-]{8,20}$",
        message = "密码必须包含大写字母、小写字母、数字和特殊字符，长度 8~20 位")
    private String password;
}
