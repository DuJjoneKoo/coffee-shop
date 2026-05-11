package com.example.coffeeshop.point.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PointChargeRequest(
        @NotNull Long userId,
        @NotNull @Min(value = 1, message = "충전 금액은 1원 이상이어야 합니다") Long amount
) {
}
