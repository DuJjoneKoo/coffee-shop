package com.example.coffeeshop.order.dto;

import com.example.coffeeshop.order.domain.Order;

import java.time.LocalDateTime;

public record OrderResponse(
        Long orderId,
        Long userId,
        Long menuId,
        long paidAmount,
        LocalDateTime createdAt
) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getMenuId(),
                order.getPaidAmount(),
                order.getCreatedAt()
        );
    }
}
