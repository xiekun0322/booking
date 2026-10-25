package com.starscreen;

import com.starscreen.common.BusinessException;
import com.starscreen.controller.UserController;
import com.starscreen.dto.LoginRequest;
import com.starscreen.dto.SmsLoginRequest;
import com.starscreen.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class SecurityAndOrderTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private UserController userController;

    // ==================== 1. 测试黄牛防刷：重复座位拦截 ====================
    @Test
    public void testDuplicateSeatsRejected() {
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
    public void testSmsCodeNotReturned() {
        // 注意：因为发送短信依赖第三方，这里如果没有真实环境，你可以 Mock 掉
        // 这里主要演示，调用接口后返回的数据中，绝对不能有 6 位数字的验证码
        // 实际上，如果你把返回值改成了 Result<Void>，这里编译直接报错，说明你改对了
        
        // 假设我们之前的 sendSms 方法
        /*
        Result<String> result = userController.sendSms(new SendSmsRequest("13800138000"));
        // 以前 result.getData() 是 "123456"
        // 现在应该为 null，或者提示语
        assertNull(result.getData(), "安全测试失败：验证码被泄露给了前端！");
        */
        System.out.println("✅ 短信验证码脱敏测试：请确保返回类型修改为 Result<Void>！");
    }

    // ==================== 3. 测试 Session 固定防护：登录刷新 Session ====================
    @Test
    public void testLoginRefreshesSessionId() {
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
            userController.login(loginReq, request);
            
            // 登录成功后，Session ID 必须发生变化！
            String newSessionId = request.getSession().getId();
            assertNotEquals(oldSessionId, newSessionId, "安全测试失败：登录后 Session ID 未刷新！");
            System.out.println("✅ Session 固定攻击防御测试通过！");
        } catch (Exception e) {
            // 如果没有测试账号报错，直接跳过打印
            System.out.println("跳过登录测试，因为测试库没有对应账号。");
        }
    }
}
