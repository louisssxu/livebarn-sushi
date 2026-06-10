package com.livebarn.sushi.dto;

import com.livebarn.sushi.model.SushiOrder;

public final class OrderMapper {

    private OrderMapper() {
    }

    public static OrderResponse toResponse(SushiOrder order) {
        return new OrderResponse(
                order.getId(),
                order.getStatusId(),
                order.getSushiId(),
                order.getCreatedAt()
        );
    }
}
