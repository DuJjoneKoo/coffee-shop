package com.example.coffeeshop.popularity.controller;

import com.example.coffeeshop.common.response.ApiResponse;
import com.example.coffeeshop.popularity.dto.PopularMenuResponse;
import com.example.coffeeshop.popularity.service.PopularityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/menus/popular")
public class PopularityController {

    private final PopularityService popularityService;

    @GetMapping
    public ApiResponse<List<PopularMenuResponse>> getPopularMenus() {
        return ApiResponse.success(popularityService.getTopPopularMenus());
    }
}
