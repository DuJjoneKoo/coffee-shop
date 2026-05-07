package com.example.coffeeshop.popularity.dto;

/**
 * OrderRepository 인기 메뉴 집계 쿼리에서 반환되는 row.
 * (menuId, 주문 횟수)
 */
public record PopularMenuRow(
        Long menuId,
        Long orderCount
) {
}
