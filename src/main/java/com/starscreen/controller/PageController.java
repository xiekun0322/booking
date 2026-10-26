package com.starscreen.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.starscreen.common.RoleConstants;
import com.starscreen.common.SessionConstants;
import com.starscreen.entity.Movie;
import com.starscreen.entity.Order;
import com.starscreen.entity.Schedule;
import com.starscreen.repository.MovieRepository;
import com.starscreen.repository.OrderRepository;
import com.starscreen.repository.ScheduleRepository;
import com.starscreen.service.AdminService;
import com.starscreen.service.OrderService;
import com.starscreen.service.SidebarService;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

/**
 * 【功能】页面控制器。
 *         所有页面不再强制 302，未登录时通过 model 传 needLogin，
 *         由模板渲染占位提示 + "立即登录"按钮（触发全局模态框）。
 */
@Controller
@RequiredArgsConstructor
public class PageController {

    private final MovieRepository movieRepository;
    private final ScheduleRepository scheduleRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final AdminService adminService;
    private final SidebarService sidebarService;
        
    // ==================== 首页：只展示 8 部 ====================
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("boxOfficeList", sidebarService.getDailyBoxOffice());
        model.addAttribute("expectedList", sidebarService.getExpectedMovies());
        model.addAttribute("top100List", sidebarService.getTop100Movies());

        List<Movie> showing = movieRepository.findByStatus("showing");
        List<Movie> upcoming = movieRepository.findByStatus("upcoming");
        model.addAttribute("showingMovies", showing.size() > 8 ? showing.subList(0, 8) : showing);
        model.addAttribute("upcomingMovies", upcoming.size() > 8 ? upcoming.subList(0, 8) : upcoming);
        return "index";
    }

    // ==================== 电影列表页：Tab + 全部 ====================
    @GetMapping("/movies")
    public String movies(@RequestParam(required = false) String keyword, Model model) {
        model.addAttribute("boxOfficeList", sidebarService.getDailyBoxOffice());
        model.addAttribute("expectedList", sidebarService.getExpectedMovies());
        model.addAttribute("top100List", sidebarService.getTop100Movies());

        if (keyword != null && !keyword.trim().isEmpty()) {
            List<Movie> searchResults = movieRepository.findByTitleContaining(keyword.trim());
            model.addAttribute("searchResults", searchResults);
            model.addAttribute("keyword", keyword);
            model.addAttribute("isSearching", true);
        } else {
            model.addAttribute("showingMovies", movieRepository.findByStatus("showing"));
            model.addAttribute("upcomingMovies", movieRepository.findByStatus("upcoming"));
            model.addAttribute("isSearching", false);
        }
        return "movies";
    }

    // ==================== 电影详情 ====================
    @SuppressWarnings("squid:S1192") // 注解屏蔽
    @GetMapping("/movie/detail/{id}")
    public String movieDetail(@PathVariable Long id, Model model) {
        model.addAttribute("movie", movieRepository.findById(id).orElse(null));
        return "movie-detail";
    }

    // ==================== 选影院 ====================
    @GetMapping("/cinemas/{movieId}")
    public String cinemas(@PathVariable Long movieId, Model model) {
        model.addAttribute("movie", movieRepository.findById(movieId).orElse(null));

        // 把过滤条件直接交给数据库，一步到位
        String today = LocalDate.now().toString();
        List<Schedule> schedules = scheduleRepository.findByMovieIdAndDateGreaterThanEqual(movieId, today);
        
        model.addAttribute("schedules", schedules);
        return "cinemas";
    }

    // ==================== 影院列表页 ====================
    @GetMapping("/cinemas")
    public String allCinemas() {
        return "all-cinemas";
    }

    // ==================== 选座 ====================
    @GetMapping("/seat/{scheduleId}")
    public String seat(@PathVariable Long scheduleId, Model model) {
        Schedule schedule = scheduleRepository.findById(scheduleId).orElse(null);
        model.addAttribute("schedule", schedule);
        if (schedule != null) {
            model.addAttribute("movie", movieRepository.findById(schedule.getMovieId()).orElse(null));
        }
        return "seat";
    }

    // ==================== 订单支付页 ====================
    @GetMapping("/order/{id}")
    public String orderDetail(@PathVariable Long id, Model model, HttpSession session) {
        Long userId = (Long) session.getAttribute(SessionConstants.USER_ID);
        if (userId == null) {
            model.addAttribute("needLogin", true);
            return "order-pay";
        }
        Order order = orderRepository.findById(id).orElse(null);
        if (order == null || !userId.equals(order.getUserId())) {
            return "redirect:/orders";
        }
        model.addAttribute("order", order);
        return "order-pay";
    }

    // ==================== 我的订单 ====================
    @GetMapping("/orders")
    public String orders(@RequestParam(required = false, defaultValue = "all") String status,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "10") int size,
                         Model model, HttpSession session) {
        Long userId = (Long) session.getAttribute(SessionConstants.USER_ID);
        if (userId == null) {
            model.addAttribute("needLogin", true);
            model.addAttribute("currentStatus", status);
            model.addAttribute("currentPage", page);
            return "orders";
        }
        Page<Order> orderPage = orderService.listPagedByUser(userId, status, page, size);
        model.addAttribute("orderPage", orderPage);
        model.addAttribute("currentStatus", status);
        model.addAttribute("currentPage", page);
        return "orders";
    }

    // ==================== 个人中心 ====================
    @GetMapping("/user/profile")
    public String userProfile() {
        return "user-profile";
    }

    @GetMapping("/user/security")
    public String userSecurity() {
        return "user-security";
    }

 // ==================== 后台管理（受 AdminInterceptor 保护） ====================
    @GetMapping("/admin")
    public String admin(Model model) {
        // 删除了冗余的 role 判断，直接查数据
        model.addAttribute("stats", adminService.getStats());
        return RoleConstants.VIEW_ADMIN;
    }

    @GetMapping("/admin/movies")
    public String adminMovies(Model model) {
        return RoleConstants.VIEW_ADMIN_MOVIES;
    }

    @GetMapping("/admin/schedules")
    public String adminSchedules(@RequestParam(required = false) Long movieId, Model model) {
        model.addAttribute("movieId", movieId);
        model.addAttribute("movies", movieRepository.findAll());
        return RoleConstants.VIEW_ADMIN_SCHEDULES;
    }

    @GetMapping("/admin/orders")
    public String adminOrders(Model model) {
        return RoleConstants.VIEW_ADMIN_ORDERS;
    }


    // ==================== 登录页 ====================
    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
