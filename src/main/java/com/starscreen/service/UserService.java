package com.starscreen.service;

import com.starscreen.common.BusinessException;
import com.starscreen.dto.BindAccountRequest;
import com.starscreen.dto.LoginRequest;
import com.starscreen.dto.RegisterRequest;
import com.starscreen.entity.User;
import com.starscreen.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    private static final String MSG_LOGIN_ERROR = "用户名或密码错误";
    private static final String MSG_USER_NOT_FOUND = "用户不存在";

    private final UserRepository userRepository;

    // ==================== 注册 ====================

    @Transactional
    public User register(RegisterRequest req) {
        if (userRepository.existsByUsernameAndDeletedFalse(req.getUsername())) {
            throw new BusinessException("用户名已存在");
        }

        // ★ 改动1：空字符串手机号统一转 null，避免唯一约束冲突
        String phone = req.getPhone();
        if (phone != null && phone.trim().isEmpty()) {
            phone = null;
        }

        if (phone != null && userRepository.existsByPhoneAndDeletedFalse(phone)) {
            throw new BusinessException("手机号已存在");
        }

        User user = new User();
        user.setUsername(req.getUsername());
        user.setPassword(PASSWORD_ENCODER.encode(req.getPassword()));
        user.setPhone(phone);   // ★ 用处理后的 phone
        user.setCreateTime(LocalDateTime.now().format(DTF));
        user.setRole("USER");
        user.setDeleted(false);

        // ★ 改动2：并发下唯一约束兜底，避免 500 "系统繁忙"
        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException("用户名或手机号已被占用");
        }

        log.info("用户注册成功：id={}, username={}", user.getId(), user.getUsername());
        return user;
    }

    // ==================== 账号密码登录 ====================

    public User login(LoginRequest req) {
        User user = userRepository.findByUsernameAndDeletedFalse(req.getUsername())
                .orElseThrow(() -> new BusinessException(MSG_LOGIN_ERROR));

        if (user.getPassword() == null || user.getPassword().isEmpty()) {
            throw new BusinessException("该账号未绑定密码，请使用手机号验证码登录");
        }

        if (!PASSWORD_ENCODER.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException(MSG_LOGIN_ERROR);
        }

        log.info("账号密码登录成功：id={}, username={}", user.getId(), user.getUsername());
        return user;
    }

    // ==================== 手机号登录/自动注册 ====================

    @Transactional
    public User loginOrRegisterByPhone(String phone) {
        return userRepository.findByPhoneAndDeletedFalse(phone).orElseGet(() -> {
            User user = new User();
            // ★ 改动3：用完整手机号，避免后4位相同导致 username 碰撞
            user.setUsername("用户" + phone);
            user.setPassword("");
            user.setPhone(phone);
            user.setCreateTime(LocalDateTime.now().format(DTF));
            user.setRole("USER");
            user.setDeleted(false);

            User saved = userRepository.save(user);
            log.info("手机号自动注册：id={}, phone={}", saved.getId(), phone);
            return saved;
        });
    }

    // ==================== 绑定账号密码 ====================

    @Transactional
    public User bindAccount(Long userId, BindAccountRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(MSG_USER_NOT_FOUND));

        if (user.getPassword() != null && !user.getPassword().isEmpty()) {
            throw new BusinessException("该账号已绑定密码，请直接使用账号密码登录");
        }

        if (userRepository.existsByUsernameAndDeletedFalse(req.getUsername())) {
            throw new BusinessException("用户名已被占用");
        }

        user.setUsername(req.getUsername());
        user.setPassword(PASSWORD_ENCODER.encode(req.getPassword()));
        user = userRepository.save(user);

        log.info("用户绑定账号密码：id={}, username={}", userId, user.getUsername());
        return user;
    }

    // ==================== 重置密码 ====================

    @Transactional
    public User resetPassword(String phone, String newPassword) {
        User user = userRepository.findByPhoneAndDeletedFalse(phone)
                .orElseThrow(() -> new BusinessException("该手机号未注册"));

        user.setPassword(PASSWORD_ENCODER.encode(newPassword));
        user = userRepository.save(user);

        log.info("用户重置密码：id={}, phone={}", user.getId(), phone);
        return user;
    }

    // ==================== 修改密码 ====================

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(MSG_USER_NOT_FOUND));

        if (user.getPassword() == null || user.getPassword().isEmpty()) {
            throw new BusinessException("该账号未设置密码，请先绑定账号");
        }

        if (!PASSWORD_ENCODER.matches(oldPassword, user.getPassword())) {
            throw new BusinessException("原密码错误");
        }

        if (PASSWORD_ENCODER.matches(newPassword, user.getPassword())) {
            throw new BusinessException("新密码不能与原密码相同");
        }

        user.setPassword(PASSWORD_ENCODER.encode(newPassword));
        userRepository.save(user);
        log.info("用户修改密码成功：id={}", userId);
    }

    // ==================== 注销账号（软删除）====================
    /**
     * 注销账号（软删除 + 释放唯一索引）
     */
    
    @Transactional
    public void deleteAccount(Long userId, String password) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(MSG_USER_NOT_FOUND));

        // 1. 校验密码是否正确（防止别人乱注销你的号）
        if (user.getPassword() != null && !user.getPassword().isEmpty() && !PASSWORD_ENCODER.matches(password, user.getPassword())) {
            throw new BusinessException("密码错误，无法注销");
        }

        // 2. 软删除
        user.setDeleted(true);

        // 3. 腾出唯一索引（加上时间戳后缀）
        long timestamp = System.currentTimeMillis();
        String oldUsername = user.getUsername();
        String safeUsername = oldUsername.length() > 20 ? oldUsername.substring(0, 20) : oldUsername;
        user.setUsername(safeUsername + "_del_" + timestamp);
        
        if (user.getPhone() != null) {
            user.setPhone("del_" + timestamp + "_" + user.getPhone());
        }

        userRepository.save(user);
        log.info("用户注销成功：id={}", userId);
    }

    // ==================== 查询 ====================

    public User getById(Long id) {
        return userRepository.findById(id).orElse(null);
    }

    public User login(String username, String password) {
        // 1. 根据用户名去数据库查这个人（注意：绝对不能查已注销的人！）
        User user = userRepository.findByUsernameAndDeletedFalse(username)
                .orElseThrow(() -> new BusinessException(MSG_LOGIN_ERROR));

        // 2. 校验密码（如果没有密码，说明是纯手机号注册用户，不能走账号密码登录）
        if (user.getPassword() == null || user.getPassword().isEmpty()) {
            throw new BusinessException("该账号未设置密码，请使用验证码登录");
        }

        // 3. 比对密码（用你项目里注入的 BCrypt 加密器）
        if (!PASSWORD_ENCODER.matches(password, user.getPassword())) {
            // 安全提示：不要提示“密码错误”，统一提示“用户名或密码错误”，防止黑客探测哪些用户名是存在的
            throw new BusinessException(MSG_LOGIN_ERROR);
        }

        // 4. 登录成功，返回用户对象
        return user;
    }
}
