package com.starscreen.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 修改密码请求。 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 20, message = "密码长度 8~20 位")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*._-])[A-Za-z\\d!@#$%^&*._-]{8,20}$",
        message = "密码必须包含大写字母、小写字母、数字和特殊字符，长度 8~20 位")
    private String newPassword;
}
