package com.starscreen.auction.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.starscreen.auction.dto.NotificationDTO;
import com.starscreen.auction.dto.PageResult;
import com.starscreen.auction.service.NotificationService;
import com.starscreen.common.Result;
import com.starscreen.common.SessionConstants;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

/**
 * 站内信接口：我的消息列表、未读数、标记已读。
 *
 * <p>身份只从 Session 取。标记已读时把 userId 一起带进条件，
 * 否则知道通知 ID 就能把别人的消息标成已读。
 */
@RestController
@RequestMapping("/api/auction/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/mine")
    public Result<PageResult<NotificationDTO>> mine(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    HttpServletRequest request) {
        Long userId = currentUserId(request);
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        return Result.success(notificationService.myList(userId, Math.max(page, 1) - 1, size));
    }

    /** 未读数：页面角标用，单独一个轻接口，避免为了一个数字拉整页列表。 */
    @GetMapping("/unread-count")
    public Result<Long> unreadCount(HttpServletRequest request) {
        Long userId = currentUserId(request);
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        return Result.success(notificationService.unreadCount(userId));
    }

    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id, HttpServletRequest request) {
        Long userId = currentUserId(request);
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        notificationService.markRead(id, userId);
        return Result.success();
    }

    @PostMapping("/read-all")
    public Result<Void> markAllRead(HttpServletRequest request) {
        Long userId = currentUserId(request);
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        notificationService.markAllRead(userId);
        return Result.success();
    }

    private Long currentUserId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (Long) session.getAttribute(SessionConstants.USER_ID);
    }
}
