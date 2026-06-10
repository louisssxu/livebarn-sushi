package com.livebarn.sushi.controller;


import com.livebarn.sushi.dto.ApiResponse;
import com.livebarn.sushi.dto.CreateOrderRequest;
import com.livebarn.sushi.dto.CreateOrderResponse;
import com.livebarn.sushi.dto.OrdersByStatusResponse;
import com.livebarn.sushi.service.OrderActionResult;
import com.livebarn.sushi.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody CreateOrderRequest request) {
        var order = orderService.createOrder(request.sushi_name());
        if (order.isEmpty()) {
            return ResponseEntity.badRequest().body(new ApiResponse(1, "Sushi not found"));
        }

        return ResponseEntity.status(201).body(
                new CreateOrderResponse(order.get(), 0, "Order created"));
    }

    @GetMapping("/status")
    public ResponseEntity<OrdersByStatusResponse> getOrdersByStatus() {
        return ResponseEntity.ok(orderService.getOrdersByStatus());
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<ApiResponse> cancelOrder(@PathVariable int orderId) {
        return toResponse(orderService.cancelOrder(orderId), "Order cancelled");
    }

    @PutMapping("/{orderId}/pause")
    public ResponseEntity<ApiResponse> pauseOrder(@PathVariable int orderId) {
        return toResponse(orderService.pauseOrder(orderId), "Order paused");
    }

    @PutMapping("/{orderId}/resume")
    public ResponseEntity<ApiResponse> resumeOrder(@PathVariable int orderId) {
        return toResponse(orderService.resumeOrder(orderId), "Order resumed");
    }

    private ResponseEntity<ApiResponse> toResponse(OrderActionResult result, String successMessage) {
        return switch (result) {
            case SUCCESS -> ResponseEntity.ok(new ApiResponse(0, successMessage));
            case NOT_FOUND -> ResponseEntity.status(404).body(new ApiResponse(1, "Order not found"));
            case INVALID_STATE -> ResponseEntity.badRequest().body(new ApiResponse(2, "Invalid order state"));
        };
    }

}
