package com.starscreen.controller;

import com.starscreen.common.Result;
import com.starscreen.dto.SidebarData;
import com.starscreen.service.SidebarService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试接口：侧边栏数据
 * 访问 /api/sidebar 可以看到 JSON
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TestController {

    private final SidebarService sidebarService;

    @GetMapping("/sidebar")
    public Result<SidebarData> sidebar() {
        SidebarData data = new SidebarData();
        data.setBoxOfficeList(sidebarService.getDailyBoxOffice());
        data.setExpectedList(sidebarService.getExpectedMovies());
        data.setTop100List(sidebarService.getTop100Movies());
        return Result.success(data);
    }
}
