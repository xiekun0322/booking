package com.starscreen.service;

import com.starscreen.common.BusinessException;
import com.starscreen.dto.LoginRequest;
import com.starscreen.dto.RegisterRequest;
import com.starscreen.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 【功能】UserService 单元测试。
 *
 * 【本次修复】
 *   1. 测试密码改为合规强密码（原来 "123456" 不满足 @Size + @Pattern）
 *   2. 断言改为 BCrypt 语义（原来断言密码是明文，BCrypt 改造后必挂）
 *   3. 增加手机号登录/注册相关用例（可选，用于验证新方法）
 *
 * 【运行方式】
 *   mvn test -Dtest=UserServiceTest
 *
 * 【依赖环境】
 *   @SpringBootTest 会启动完整 Spring 上下文，依赖 MySQL 连接。
 *
 * 【事务回滚】
 *   @Transactional → 每个测试方法结束后自动回滚。
 */
@SpringBootTest
@Transactional
class UserServiceTest {

    /** 合规强密码：至少 8 位，含大小写字母 + 数字 + 特殊字符（仅测试用，勿使用真实密码） */
    private static final String VALID_PWD = "Test@1234";

    /** 另一个合规强密码（用于"密码错误"场景） */
    private static final String ANOTHER_PWD = "Xyz#5678";

    @Autowired
    private UserService userService;

    /** BCrypt 校验器：用于验证"密码是否真被加密" */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // ==================== 注册 ====================

    /**
     * 【测试】注册成功路径。
     * 【验证】
     *   - 返回的 User 有 ID（已入库）
     *   - username 与请求一致
     *   - 密码【已加密】（与明文不同，且 BCrypt.matches 能匹配）
     *   - createTime 已自动填写
     * 【修复点】
     *   原来用 "123456"（不满足强密码）→ 改为 VALID_PWD
     *   原来断言 "123456".equals(password) → 改为 BCrypt.matches
     */
    @Test
    @DisplayName("注册成功：用户对象入库，密码 BCrypt 加密")
    void testRegister_Success() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("test_user_" + System.currentTimeMillis());
        req.setPassword(VALID_PWD);
        req.setPhone("13800138000");

        User user = userService.register(req);

        assertNotNull(user.getId(), "用户应有 ID");
        assertEquals(req.getUsername(), user.getUsername(), "用户名应一致");

        // ★ BCrypt 校验：密码已加密，且能用原密码匹配
        assertNotEquals(VALID_PWD, user.getPassword(), "密码不应是明文");
        assertTrue(passwordEncoder.matches(VALID_PWD, user.getPassword()),
                "BCrypt 应能用原密码匹配");

        assertNotNull(user.getCreateTime(), "createTime 应自动填写");
    }

    /**
     * 【测试】用户名重复场景。
     * 【验证】第二次注册相同用户名应抛 BusinessException，消息包含 "已存在"。
     */
    @Test
    @DisplayName("用户名重复：抛 BusinessException")
    void testRegister_DuplicateUsername() {
        String username = "dup_user_" + System.currentTimeMillis();

        RegisterRequest req1 = new RegisterRequest();
        req1.setUsername(username);
        req1.setPassword(VALID_PWD);
        userService.register(req1);

        RegisterRequest req2 = new RegisterRequest();
        req2.setUsername(username);
        req2.setPassword(ANOTHER_PWD);

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            userService.register(req2);
        });
        assertTrue(ex.getMessage().contains("已存在"), "异常信息应提示用户名已存在");
    }

    // ==================== 登录 ====================

    /**
     * 【测试】登录成功路径。
     * 【验证】用正确密码登录应返回对应 User。
     * 【修复点】密码从 "abcdef" 改为 VALID_PWD。
     */
    @Test
    @DisplayName("登录成功：密码正确")
    void testLogin_Success() {
        String username = "login_user_" + System.currentTimeMillis();

        // 先注册
        RegisterRequest regReq = new RegisterRequest();
        regReq.setUsername(username);
        regReq.setPassword(VALID_PWD);
        userService.register(regReq);

        // 再用正确密码登录
        LoginRequest loginReq = new LoginRequest();
        loginReq.setUsername(username);
        loginReq.setPassword(VALID_PWD);

        User loggedIn = userService.login(loginReq);
        assertEquals(username, loggedIn.getUsername(), "登录返回的用户名应一致");
    }

    /**
     * 【测试】登录失败：密码错误。
     * 【验证】错误密码登录应抛 BusinessException。
     * 【安全意义】
     *   异常消息统一为"用户名或密码错误"，
     *   不区分"用户不存在"和"密码错误"，防止攻击者探测有效用户名。
     */
    @Test
    @DisplayName("登录失败：密码错误")
    void testLogin_WrongPassword() {
        String username = "wrong_pwd_" + System.currentTimeMillis();

        RegisterRequest regReq = new RegisterRequest();
        regReq.setUsername(username);
        regReq.setPassword(VALID_PWD);
        userService.register(regReq);

        LoginRequest loginReq = new LoginRequest();
        loginReq.setUsername(username);
        loginReq.setPassword(ANOTHER_PWD);   // 错误密码

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            userService.login(loginReq);
        });
        assertEquals("用户名或密码错误", ex.getMessage(),
                "不区分用户不存在/密码错误，统一提示");
    }

    /**
     * 【测试】登录失败：用户不存在。
     * 【验证】用户名不存在时，提示也是"用户名或密码错误"（防用户名枚举）。
     */
    @Test
    @DisplayName("登录失败：用户名不存在")
    void testLogin_UserNotFound() {
        LoginRequest loginReq = new LoginRequest();
        loginReq.setUsername("not_exist_user_" + System.currentTimeMillis());
        loginReq.setPassword(VALID_PWD);

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            userService.login(loginReq);
        });
        assertEquals("用户名或密码错误", ex.getMessage(),
                "用户不存在也返回统一提示");
    }

    // ==================== 手机号登录/注册 ====================

    /**
     * 【测试】手机号登录/自动注册。
     * 【验证】首次用手机号登录 → 自动创建用户
     *         再次用同一手机号 → 返回同一用户
     * 【前置】UserService.loginOrRegisterByPhone(phone)
     */
    @Test
    @DisplayName("手机号登录：首次自动注册，再次返回同一用户")
    void testLoginOrRegisterByPhone() {
        String phone = "1390013" + String.format("%04d", System.currentTimeMillis() % 10000);

        User first = userService.loginOrRegisterByPhone(phone);
        assertNotNull(first.getId(), "首次登录应自动创建用户");
        assertEquals(phone, first.getPhone(), "手机号应一致");
        assertNotNull(first.getUsername(), "应自动生成用户名");

        User second = userService.loginOrRegisterByPhone(phone);
        assertEquals(first.getId(), second.getId(), "再次登录应返回同一用户");
    }

    // ==================== 绑定账号 ====================

    /**
     * 【测试】绑定账号密码。
     * 【验证】
     *   1. 手机号注册的用户（password 为空）→ 可以绑定
     *   2. 绑定后，账号密码登录成功
     *   3. 再次绑定 → 抛"已绑定"
     */
    @Test
    @DisplayName("绑定账号：手机号用户可绑定，绑定后可用密码登录")
    void testBindAccount() {
        String phone = "1380014" + String.format("%04d", System.currentTimeMillis() % 10000);
        User user = userService.loginOrRegisterByPhone(phone);

        String newUsername = "bind_user_" + System.currentTimeMillis();
        com.starscreen.dto.BindAccountRequest bindReq = new com.starscreen.dto.BindAccountRequest();
        bindReq.setUsername(newUsername);
        bindReq.setPassword(VALID_PWD);

        User bound = userService.bindAccount(user.getId(), bindReq);
        assertEquals(newUsername, bound.getUsername(), "用户名应更新");
        assertTrue(passwordEncoder.matches(VALID_PWD, bound.getPassword()),
                "密码应 BCrypt 加密");

        // 用账号密码登录
        LoginRequest loginReq = new LoginRequest();
        loginReq.setUsername(newUsername);
        loginReq.setPassword(VALID_PWD);
        User loggedIn = userService.login(loginReq);
        assertEquals(user.getId(), loggedIn.getId(), "登录应返回同一用户");

        // 再次绑定 → 抛异常
        BusinessException ex = assertThrows(BusinessException.class, () -> {
            userService.bindAccount(user.getId(), bindReq);
        });
        assertTrue(ex.getMessage().contains("已绑定"), "应提示已绑定");
    }

    // ==================== 重置密码 ====================

    /**
     * 【测试】重置密码。
     * 【验证】
     *   1. 重置后，用新密码登录成功
     *   2. 用旧密码登录失败
     */
    @Test
    @DisplayName("重置密码：新密码可登录，旧密码失效")
    void testResetPassword() {
        String phone = "1380015" + String.format("%04d", System.currentTimeMillis() % 10000);
        User user = userService.loginOrRegisterByPhone(phone);

        // 先绑定一个密码
        com.starscreen.dto.BindAccountRequest bindReq = new com.starscreen.dto.BindAccountRequest();
        bindReq.setUsername("reset_user_" + System.currentTimeMillis());
        bindReq.setPassword(VALID_PWD);
        userService.bindAccount(user.getId(), bindReq);

        // 重置为新密码
        String newPwd = "NewP@ss99";
        userService.resetPassword(phone, newPwd);

        // 新密码登录成功
        LoginRequest loginReq = new LoginRequest();
        loginReq.setUsername(bindReq.getUsername());
        loginReq.setPassword(newPwd);
        User loggedIn = userService.login(loginReq);
        assertEquals(user.getId(), loggedIn.getId(), "新密码应能登录");

        // 旧密码登录失败
        LoginRequest oldReq = new LoginRequest();
        oldReq.setUsername(bindReq.getUsername());
        oldReq.setPassword(VALID_PWD);
        assertThrows(BusinessException.class, () -> userService.login(oldReq),
                "旧密码应失效");
    }

    /**
     * 【测试】重置密码：手机号未注册。
     */
    @Test
    @DisplayName("重置密码：手机号未注册抛异常")
    void testResetPassword_PhoneNotFound() {
        String phone = "19999999999";
        BusinessException ex = assertThrows(BusinessException.class, () -> {
            userService.resetPassword(phone, "NewP@ss99");
        });
        assertTrue(ex.getMessage().contains("未注册"), "应提示手机号未注册");
    }
}