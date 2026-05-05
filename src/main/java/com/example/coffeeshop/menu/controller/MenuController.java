package com.example.coffeeshop.menu.controller;

import com.example.coffeeshop.common.response.ApiResponse;
import com.example.coffeeshop.menu.dto.MenuResponse;
import com.example.coffeeshop.menu.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/menus")
public class MenuController {

    private final MenuService menuService;

    @GetMapping
    public ApiResponse<List<MenuResponse>> getMenus() {
        return ApiResponse.success(menuService.getAllMenus());
    }
}
