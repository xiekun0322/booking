package com.starscreen.service;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.starscreen.common.BusinessException;

/**
 * 【功能】模拟短信验证码服务。
 *
 * 【演示环境】验证码打印到 Console，同时通过 Result.data 返回给前端。
 * 【生产环境】应接入阿里云/腾讯云短信，且【不返回】验证码。
 */
@Service
public class SmsService {
   
	private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    private static final SecureRandom secureRandom = new SecureRandom();
    
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
        String code = String.format("%06d", secureRandom.nextInt(1000000));
        
        // 存入 Redis，设置 5 分钟过期
        redisTemplate.opsForValue().set("sms:code:" + phone, code, 5, TimeUnit.MINUTES);

        // 这里调用真正的第三方短信网关（目前用控制台打印模拟）
        log.debug("[模拟发送短信] 给 {} 发送验证码: {}", phone, code);
    }

    /**
     * 【功能】校验验证码。
     */
    /**
     * 【功能】校验验证码。
     */
    public void verifyCode(String phone, String code) {
        // 1. 直接从 Redis 里拿（注意 key 的拼接要和上面 sendCode 保持一致）
        String redisKey = "sms:code:" + phone;
        String cachedCode = redisTemplate.opsForValue().get(redisKey);

        // 2. 如果拿不到，说明根本没发过，或者已经过了 5 分钟被 Redis 自动删了
        if (cachedCode == null) {
            throw new BusinessException("验证码未发送或已过期");
        }

        // 3. 比对验证码（忽略大小写，虽然都是数字，但好习惯）
        if (!cachedCode.equalsIgnoreCase(code)) {
            throw new BusinessException("验证码错误");
        }

        // 4. 验证成功后，立刻把 Redis 里的验证码删掉（防重放攻击：一个验证码只能用一次！）
        redisTemplate.delete(redisKey);
    }
}
