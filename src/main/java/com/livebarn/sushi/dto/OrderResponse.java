package com.livebarn.sushi.dto;

import java.sql.Timestamp;

public record OrderResponse(Integer id, Integer statusId, Integer sushiId, Timestamp createdAt) {
}
