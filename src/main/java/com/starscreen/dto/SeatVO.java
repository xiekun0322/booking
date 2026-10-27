package com.starscreen.dto;

import lombok.Data;

/**
 * 座位展示对象（VO），比 {@code Seat} 实体多了拼接好的 label。
 *
 * <p>设计动机：
 * <ol>
 *   <li>不暴露 {@code Seat.orderId}，防止前端窥探他人订单；</li>
 *   <li>前端可直接使用 label，省去拼接；</li>
 *   <li>未来实体加内部字段时 VO 结构稳定。</li>
 * </ol>
 *
 * <pre>
 *   { "id":123, "rowNum":3, "colNum":5, "status":"available", "label":"3排5座" }
 * </pre>
 */
@Data
public class SeatVO {

    private Long    id;
    private Integer rowNum;
    private Integer colNum;

    /** available / locked / sold。 */
    private String  status;

    /** "3排5座"，由 SeatService 拼接。 */
    private String  label;
}
