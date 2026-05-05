package com.example.coffeeshop.menu.dto;

import com.example.coffeeshop.menu.domain.Menu;

public record MenuResponse(
        Long menuId,
        String name,
        long price
) {

    public static MenuResponse from(Menu menu) {
        return new MenuResponse(menu.getId(), menu.getName(), menu.getPrice());
    }
}
