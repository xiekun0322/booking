package com.starscreen.controller;

import com.starscreen.common.Result;
import com.starscreen.dto.*;
import com.starscreen.entity.User;
import com.starscreen.service.SmsService;
import com.starscreen.service.UserService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final SmsService smsService;

    /** 注册 */
    @PostMapping("/register")
    public Result<UserVO> register(@RequestBody @Valid RegisterRequest req) {
        return Result.success(UserVO.from(userService.register(req)));
    }

    /** 账号密码登录 */
    @PostMapping("/login")
    public Result<UserVO> login(@RequestBody @Valid LoginRequest req, HttpSession session) {
        User user = userService.login(req);
        writeSession(session, user);
        return Result.success(UserVO.from(user));
    }

    /** 短信验证码登录 */
    @PostMapping("/login-sms")
    public Result<UserVO> loginBySms(@RequestBody @Valid SmsLoginRequest req, HttpSession session) {
        smsService.verifyCode(req.getPhone(), req.getCode());
        User user = userService.loginOrRegisterByPhone(req.getPhone());
        writeSession(session, user);
        return Result.success(UserVO.from(user));
    }

    /** 发送短信验证码（返回验证码，仅演示环境） */
    @PostMapping("/send-sms")
    public Result<String> sendSms(@RequestBody @Valid SendSmsRequest req) {
        String code = smsService.sendCode(req.getPhone());
        return Result.success(code);
    }

    /** 绑定账号密码 */
    @PostMapping("/bind-account")
    public Result<UserVO> bindAccount(@RequestBody @Valid BindAccountRequest req, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) return Result.error(401, "请先登录");
        User user = userService.bindAccount(userId, req);
        return Result.success(UserVO.from(user));
    }

    /** 重置密码 */
    @PostMapping("/reset-password")
    public Result<Void> resetPassword(@RequestBody @Valid ResetPasswordRequest req) {
        smsService.verifyCode(req.getPhone(), req.getCode());
        userService.resetPassword(req.getPhone(), req.getNewPassword());
        return Result.success();
    }

    /** 修改密码 */
    @PostMapping("/change-password")
    public Result<Void> changePassword(@RequestBody @Valid ChangePasswordRequest req,
                                        HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) return Result.error(401, "请先登录");
        userService.changePassword(userId, req.getOldPassword(), req.getNewPassword());
        return Result.success();
    }

    /** ★ 注销账号（软删除） */
    @PostMapping("/delete-account")
    public Result<Void> deleteAccount(@RequestBody @Valid DeleteAccountRequest req,
                                       HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) return Result.error(401, "请先登录");
        userService.deleteAccount(userId, req.getPassword());
        session.invalidate();
        return Result.success();
    }

    /** 登出 */
    @PostMapping("/logout")
    public Result<Void> logout(HttpSession session) {
        session.invalidate();
        return Result.success();
    }

    /** 当前登录用户 */
    @GetMapping("/current")
    public Result<UserVO> current(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) return Result.error(401, "未登录");
        User user = userService.getById(userId);
        if (user == null || Boolean.TRUE.equals(user.getDeleted())) {
            session.invalidate();
            return Result.error(401, "登录已失效");
        }
        return Result.success(UserVO.from(user));
    }

    /** 统一写 Session */
    private void writeSession(HttpSession session, User user) {
        session.setAttribute("userId", user.getId());
        session.setAttribute("username", user.getUsername());
        session.setAttribute("role", user.getRole() != null ? user.getRole() : "USER");
    }
}