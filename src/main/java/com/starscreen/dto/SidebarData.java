package com.starscreen.dto;

import java.util.List;

import com.starscreen.entity.DailyBoxOffice;
import com.starscreen.entity.ExpectedMovie;
import com.starscreen.entity.Top100Movie;

import lombok.Data;

/** 首页侧边栏三个榜单聚合。 */
@Data
public class SidebarData {

    private List<DailyBoxOffice> boxOfficeList;
    private List<ExpectedMovie>  expectedList;
    private List<Top100Movie>    top100List;

    /** 今日大盘总票房。 */
    private Double totalAmount;
}
