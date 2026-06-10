package com.livebarn.sushi.dto;

public record CreateOrderResponse(OrderResponse order, int code, String msg) {
}
