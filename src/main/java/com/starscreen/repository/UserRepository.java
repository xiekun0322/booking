package com.starscreen.repository;

import com.starscreen.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 【功能】用户数据访问层。
 * 【软删除】以 AndDeletedFalse 结尾的方法，自动过滤已注销用户。
 */
public interface UserRepository extends JpaRepository<User, Long> {

    // ==================== 软删除过滤（推荐使用）====================

    /** 按用户名查（排除已注销） */
    Optional<User> findByUsernameAndDeletedFalse(String username);

    /** 按手机号查（排除已注销） */
    Optional<User> findByPhoneAndDeletedFalse(String phone);

    /** 判断用户名是否存在（排除已注销） */
    boolean existsByUsernameAndDeletedFalse(String username);

    /** 判断手机号是否存在（排除已注销） */
    boolean existsByPhoneAndDeletedFalse(String phone);
}
