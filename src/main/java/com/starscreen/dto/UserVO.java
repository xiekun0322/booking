package com.starscreen.dto;

import com.starscreen.entity.User;

import lombok.Data;

/** 用户展示对象：屏蔽密码等敏感字段。 */
@Data
public class UserVO {

    private Long   id;
    private String username;
    private String phone;
    private String createTime;

    /** USER / ADMIN。 */
    private String role;

    public static UserVO from(User user) {
        if (user == null) return null;
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setPhone(user.getPhone());
        vo.setCreateTime(user.getCreateTime());
        vo.setRole(user.getRole());
        return vo;
    }
}
