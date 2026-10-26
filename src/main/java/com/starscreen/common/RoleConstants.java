package com.starscreen.common;

/**
 * 角色与视图名常量。
 *
 * <p>注意两组常量语义不同：
 * <ul>
 *   <li>{@link #ADMIN} / {@link #USER}：存库/放 Session 的<b>角色值</b>；</li>
 *   <li>{@code VIEW_*}：返回给 Thymeleaf 的<b>视图名</b>。</li>
 * </ul>
 */
public final class RoleConstants {

    public static final String ADMIN = "ADMIN";
    public static final String USER  = "USER";

    public static final String VIEW_ADMIN           = "admin";
    public static final String VIEW_ADMIN_MOVIES    = "admin_movies";
    public static final String VIEW_ADMIN_SCHEDULES = "admin_schedules";
    public static final String VIEW_ADMIN_ORDERS    = "admin_orders";

    private RoleConstants() {}
}