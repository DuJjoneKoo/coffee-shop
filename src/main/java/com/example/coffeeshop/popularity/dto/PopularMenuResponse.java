package com.example.coffeeshop.popularity.dto;

public record PopularMenuResponse(
        Long menuId,
        String name,
        long price,
        long orderCount
) {
}
