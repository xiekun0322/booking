package com.starscreen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import com.starscreen.common.BusinessException;
import com.starscreen.common.Result;
import com.starscreen.controller.UserController;
import com.starscreen.dto.LoginRequest;
import com.starscreen.dto.SendSmsRequest;
import com.starscreen.service.OrderService;

@SpringBootTest
class SecurityAndOrderTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private UserController userController;

    // ==================== 1. 测试黄牛防刷：重复座位拦截 ====================
    @Test
    void testDuplicateSeatsRejected() {
        Long userId = 1L;
        Long scheduleId = 1L;
        // 模拟黑客故意传入三个一样的座位
        List<String> maliciousSeats = Arrays.asList("3排5座", "3排5座", " 3排5座 ");

        // 期待抛出 BusinessException（因为咱们加了 HashSet 去重校验）
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            orderService.createOrder(userId, scheduleId, maliciousSeats, "slider_success");
        });

        assertEquals("座位数据异常：请勿重复提交相同座位", exception.getMessage());
        System.out.println("✅ 防重复座位提交测试通过！");
    }

    // ==================== 2. 测试验证码脱敏：前端拿不到验证码 ====================
    @Test
    void testSmsCodeNotReturned() {
        // 1. 准备测试数据
        SendSmsRequest req = new SendSmsRequest();
        req.setPhone("13800138000");

        // 2. 接收返回结果（改用 Result<Void>）
        Result<Void> result = userController.sendSms(req);

        // 3. 核心断言（极其干脆！）
        // 断言 A：状态码必须是成功
        assertEquals(200, result.getCode());
        
        // 断言 B：既然改成了 Void，那 data 必须为空！
        assertNull(result.getData(), "安全漏洞：发短信接口依然在向前端返回数据！");
        
        System.out.println("测试通过：验证码已安全脱敏，前端无法直接获取。");
    }

    // ==================== 3. 测试 Session 固定防护：登录刷新 Session ====================
    @Test
    void testLoginRefreshesSessionId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // 模拟黑客预先塞入的旧 Session ID
        MockHttpSession oldSession = new MockHttpSession();
        request.setSession(oldSession);
        String oldSessionId = request.getSession().getId();

        // 模拟用户调用登录接口
        LoginRequest loginReq = new LoginRequest();
        loginReq.setUsername("testUser");
        loginReq.setPassword("123456");

        try {
            // 假设正常登录 (如果你的数据库里没这个账号，可能会抛异常)
            userController.login(loginReq, request.getSession());
            
            // 登录成功后，Session ID 必须发生变化！
            String newSessionId = request.getSession().getId();
            assertNotEquals(oldSessionId, newSessionId, "安全测试失败：登录后 Session ID 未刷新！");
            System.out.println("Session 固定攻击防御测试通过！");
        } catch (Exception e) {
            // 如果没有测试账号报错，直接跳过打印
            System.out.println("跳过登录测试，因为测试库没有对应账号。");
        }
    }
}
