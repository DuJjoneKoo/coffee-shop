package com.example.coffeeshop.point.controller;

import com.example.coffeeshop.common.response.ApiResponse;
import com.example.coffeeshop.point.dto.PointChargeRequest;
import com.example.coffeeshop.point.dto.PointResponse;
import com.example.coffeeshop.point.service.PointService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/points")
public class PointController {

    private final PointService pointService;

    @PostMapping("/charge")
    public ApiResponse<PointResponse> charge(@Valid @RequestBody PointChargeRequest request) {
        return ApiResponse.success(pointService.charge(request.userId(), request.amount()));
    }

    @GetMapping("/{userId}")
    public ApiResponse<PointResponse> getBalance(@PathVariable Long userId) {
        return ApiResponse.success(pointService.getBalance(userId));
    }
}
