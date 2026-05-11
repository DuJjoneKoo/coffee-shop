package com.example.coffeeshop.order.controller;

import com.example.coffeeshop.common.response.ApiResponse;
import com.example.coffeeshop.order.dto.OrderRequest;
import com.example.coffeeshop.order.dto.OrderResponse;
import com.example.coffeeshop.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ApiResponse<OrderResponse> order(@Valid @RequestBody OrderRequest request) {
        return ApiResponse.success(orderService.order(request.userId(), request.menuId()));
    }
}
