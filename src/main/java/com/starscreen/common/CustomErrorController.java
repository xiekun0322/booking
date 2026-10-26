package com.starscreen.common;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 全局错误页：兜住所有未被 {@link GlobalExceptionHandler} 捕获的容器级错误。
 *
 * <p>典型来源：静态资源 404、容器层 4xx/5xx。
 *
 * <p><b>NOTE</b>：返回值是 {@code Result{code=404}}，但 HTTP 状态码是 200，
 * 监控/爬虫无法通过状态码区分 404。若需要真正的 404 语义，
 * 应改返回 {@code ResponseEntity} 并设置 status。
 */
@RestController
public class CustomErrorController implements ErrorController {

    @RequestMapping("/error")
    public Result<Void> handleError(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (status != null) {
            int statusCode = Integer.parseInt(status.toString());
            if (statusCode == 404) {
                return Result.error(404, "抱歉，您访问的页面或接口不存在");
            }
            if (statusCode == 401) {
                return Result.error(401, "请先登录");
            }
            if (statusCode == 403) {
                return Result.error(403, "无权访问");
            }
        }
        return Result.error(500, "系统繁忙，请稍后重试");
    }
}
