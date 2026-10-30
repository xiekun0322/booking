package com.starscreen.controller;

import com.starscreen.common.Result;
import com.starscreen.common.SessionConstants;
import com.starscreen.dto.*;
import com.starscreen.entity.User;
import com.starscreen.service.SmsService;
import com.starscreen.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final SmsService smsService;

    /**
     * 是否把验证码明文放进 Result.data 返回给前端。
     *
     * <p>默认 false（生产安全默认）；演示/联调环境在 application.properties 置 true。
     * 前端（auth.js 弹窗、login.html）按"data 有 6 位数字就自动填入，否则提示已发送"处理，
     * 两种情况都不会再出现 alert(null)、输入框被清空。
     */
    @Value("${app.sms.return-code:false}")
    private boolean smsReturnCode;

    /** 注册 */
    @PostMapping("/register")
    public Result<UserVO> register(@RequestBody @Valid RegisterRequest req) {
        return Result.success(UserVO.from(userService.register(req)));
    }

    /** 账号密码登录 */
    @PostMapping("/login")
    public Result<UserVO> login(@RequestBody @Valid LoginRequest req, HttpServletRequest request) {
        User user = userService.login(req);
        writeSession(request, user);
        return Result.success(UserVO.from(user));
    }

    /** 短信验证码登录 */
    @PostMapping("/login-sms")
    public Result<UserVO> loginBySms(@RequestBody @Valid SmsLoginRequest req, HttpServletRequest request) {
        smsService.verifyCode(req.getPhone(), req.getCode());
        User user = userService.loginOrRegisterByPhone(req.getPhone());
        writeSession(request, user);
        return Result.success(UserVO.from(user));
    }

    /**
     * 发送短信验证码。
     *
     * <p>返回契约（与前端 auth.js / login.html 对齐）：
     * <ul>
     *   <li>{@code app.sms.return-code=true}（演示环境）→ data = 6 位验证码，前端自动填入；</li>
     *   <li>{@code app.sms.return-code=false}（生产）→ data = null，前端提示"验证码已发送"。</li>
     * </ul>
     * 生产环境务必保持 false：验证码只经短信下发，不能再从接口里被读走。
     */
    @PostMapping("/send-sms")
    public Result<String> sendSms(@RequestBody @Valid SendSmsRequest req) {
        String code = smsService.sendCode(req.getPhone());
        if (smsReturnCode) {
            return Result.success(code);
        }
        return Result.success();
    }

    /** 绑定账号密码 */
    @PostMapping("/bind-account")
    public Result<UserVO> bindAccount(@RequestBody @Valid BindAccountRequest req, HttpSession session) {
        Long userId = (Long) session.getAttribute(SessionConstants.USER_ID);
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
        Long userId = (Long) session.getAttribute(SessionConstants.USER_ID);
        if (userId == null) return Result.error(401, "请先登录");
        userService.changePassword(userId, req.getOldPassword(), req.getNewPassword());
        return Result.success();
    }

    /** ★ 注销账号（软删除） */
    @PostMapping("/delete-account")
    public Result<Void> deleteAccount(@RequestBody @Valid DeleteAccountRequest req,
                                       HttpSession session) {
        Long userId = (Long) session.getAttribute(SessionConstants.USER_ID);
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
        Long userId = (Long) session.getAttribute(SessionConstants.USER_ID);
        if (userId == null) return Result.error(401, "未登录");
        User user = userService.getById(userId);
        if (user == null || Boolean.TRUE.equals(user.getDeleted())) {
            session.invalidate();
            return Result.error(401, "登录已失效");
        }
        return Result.success(UserVO.from(user));
    }

    /**
     * 统一写 Session。
     *
     * <p>★ 防会话固定（Session Fixation）：必须先把 Session ID 换掉，再写入登录身份。
     * 攻击路径是——攻击者先拿到一个匿名会话的 JSESSIONID 并诱导受害者使用（URL 传参、
     * 子域写 Cookie 等），受害者在该会话上登录后，攻击者手里那个旧 ID 就直接变成了
     * 已登录会话，可读取订单、以受害者身份下单。
     *
     * <p>{@link HttpServletRequest#changeSessionId()} 是 Servlet 3.1 起的标准做法：容器换发
     * 新 ID 并回写 Set-Cookie，会话内属性保持不动（同一 session 对象，不新建）。
     * 注意该方法在 {@code HttpServletRequest} 上，不在 {@code HttpSession} 上。
     * 相比 {@code invalidate()} + 新建会话，它不会丢掉本次请求已写入的其它属性，
     * 也不影响 Thymeleaf 里对 {@code session} 的读取。
     */
    private void writeSession(HttpServletRequest request, User user) {
        HttpSession session = request.getSession();
        // 请求里还没挂会话时，changeSessionId 会抛 IllegalStateException；
        // getSession() 保证会话已创建并挂到当前请求。
        request.changeSessionId();
        session.setAttribute(SessionConstants.USER_ID, user.getId());
        session.setAttribute("username", user.getUsername());
        session.setAttribute("role", user.getRole() != null ? user.getRole() : "USER");
    }
}
