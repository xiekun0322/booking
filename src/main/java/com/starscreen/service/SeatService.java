package com.starscreen.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.starscreen.dto.SeatVO;
import com.starscreen.entity.Seat;
import com.starscreen.repository.SeatRepository;

/**
 * 【功能】座位业务服务。
 *         目前只有一个功能：按场次查询座位，并转成 SeatVO。
 *
 * 【调用方】
 *          - SeatController.listBySchedule(scheduleId)
 *          - seat.html 前端 fetch('/api/seats/schedule/{scheduleId}')
 *
 * 【被调用】
 *          - SeatRepository.findByScheduleIdOrderByRowNumAscColNumAsc
 *          - SeatVO（构造展示对象）
 *
 * 【为什么需要转 VO】
 *          1. Seat 实体含 orderId，直接暴露会泄露订单关联信息
 *          2. SeatVO 加 label 字段，省去前端拼接
 */
@Service
public class SeatService {

    @Autowired
    private SeatRepository seatRepository;

    /**
     * 【功能】查询某场次的座位列表，按排 + 座升序。
     * 【调用链】
     *   SeatController.listBySchedule(scheduleId)
     *   → GET /api/seats/schedule/{scheduleId}
     *   → SeatService.listBySchedule(scheduleId)
     *   → seatRepository.findBy...（Entity）
     *   → 转成 SeatVO 列表返回前端
     * 【性能】IMAX 厅 160 条，普通厅 60 条，一次查询可接受。
     * @param scheduleId 场次 ID
     * @return 座位 VO 列表，label 已拼好（"3排5座"）
     */
    public List<SeatVO> listBySchedule(Long scheduleId) {
        List<Seat> seats = seatRepository
                .findByScheduleIdOrderByRowNumAscColNumAsc(scheduleId);

        List<SeatVO> result = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            SeatVO vo = new SeatVO();
            vo.setId(seat.getId());
            vo.setRowNum(seat.getRowNum());
            vo.setColNum(seat.getColNum());
            vo.setStatus(seat.getStatus());
            vo.setLabel(seat.getRowNum() + "排" + seat.getColNum() + "座");
            // 注意：不设置 orderId，防止前端窥探订单
            result.add(vo);
        }
        return result;
    }
}
