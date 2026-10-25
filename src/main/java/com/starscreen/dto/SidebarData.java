package com.starscreen.dto;

import com.starscreen.entity.DailyBoxOffice;
import com.starscreen.entity.ExpectedMovie;
import com.starscreen.entity.Top100Movie;
import lombok.Data;

import java.util.List;

/**
 * 侧边栏数据聚合
 */
@Data
public class SidebarData {

    private List<DailyBoxOffice> boxOfficeList;
    private List<ExpectedMovie> expectedList;
    private List<Top100Movie> top100List;

    /** 今日大盘总票房 */
    private Double totalAmount;
}
