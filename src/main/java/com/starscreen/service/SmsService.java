package com.starscreen.service;

import com.starscreen.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

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
    public String sendCode(String phone) {
        String code = String.format("%06d", random.nextInt(1000000));

        codeStore.put(phone, new CodeEntry(code, System.currentTimeMillis() + CODE_TTL_MS));

        log.info("========================================");
        log.info("★ 模拟短信发送");
        log.info("  手机号：{}", phone);
        log.info("  验证码：{}", code);
        log.info("  有效期：5 分钟");
        log.info("========================================");

        return code;   // ★ 返回验证码
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
}