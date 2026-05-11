package com.example.coffeeshop.order.dto;

import jakarta.validation.constraints.NotNull;

public record OrderRequest(
        @NotNull Long userId,
        @NotNull Long menuId
) {
}
