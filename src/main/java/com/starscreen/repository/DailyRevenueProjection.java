package com.starscreen.repository;

public interface DailyRevenueProjection {
    String getDay();
    Double getTotalRevenue();
    Long getOrderCount();
}
