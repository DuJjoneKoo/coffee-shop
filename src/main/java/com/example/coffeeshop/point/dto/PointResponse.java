package com.example.coffeeshop.point.dto;

import com.example.coffeeshop.point.domain.UserPoint;

public record PointResponse(
        Long userId,
        long balance
) {

    public static PointResponse from(UserPoint userPoint) {
        return new PointResponse(userPoint.getUserId(), userPoint.getBalance());
    }
}
