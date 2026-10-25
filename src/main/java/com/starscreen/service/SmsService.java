package com.starscreen.service;

import com.starscreen.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 【功能】模拟短信验证码服务。
 *
 * 【演示环境】验证码打印到 Console，同时通过 Result.data 返回给前端。
 * 【生产环境】应接入阿里云/腾讯云短信，且【不返回】验证码。
 */
@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    private static final long CODE_TTL_MS = 5 * 60 * 1000;

    private final Map<String, CodeEntry> codeStore = new ConcurrentHashMap<>();
    private final Random random = new Random();

    /**
     * 【功能】发送验证码（模拟）。
     * 【返回值】★ 验证码明文（演示环境用，生产环境应返回 void）
     */
    @Autowired
    private StringRedisTemplate redisTemplate;

    public void sendCode(String phone) {
        // 🚨 防刷机制：同一个手机号，1 分钟内只能发 1 次
        String limitKey = "sms:limit:" + phone;
        Boolean isAllowed = redisTemplate.opsForValue().setIfAbsent(limitKey, "1", 1, TimeUnit.MINUTES);
        
        if (Boolean.FALSE.equals(isAllowed)) {
            throw new BusinessException("发送太频繁，请1分钟后再试");
        }

        // 生成 6 位随机验证码
        String code = String.format("%06d", new java.util.Random().nextInt(1000000));
        
        // 存入 Redis，设置 5 分钟过期
        redisTemplate.opsForValue().set("sms:code:" + phone, code, 5, TimeUnit.MINUTES);

        // 这里调用真正的第三方短信网关（目前用控制台打印模拟）
        System.out.println("[模拟发送短信] 给 " + phone + " 发送验证码：" + code);
    }

    /**
     * 【功能】校验验证码。
     */
    public void verifyCode(String phone, String code) {
        CodeEntry entry = codeStore.get(phone);
        if (entry == null) {
            throw new BusinessException("验证码未发送或已过期");
        }
        if (entry.expireAt < System.currentTimeMillis()) {
            codeStore.remove(phone);
            throw new BusinessException("验证码已过期");
        }
        if (!entry.code.equals(code)) {
            throw new BusinessException("验证码错误");
        }
        codeStore.remove(phone);
    }

    private static class CodeEntry {
        final String code;
        final long expireAt;
        CodeEntry(String code, long expireAt) {
            this.code = code;
            this.expireAt = expireAt;
        }
    }

	public boolean verifyCaptcha(String captchaTicket) {
		// TODO 自动生成的方法存根
		return false;
	}
}