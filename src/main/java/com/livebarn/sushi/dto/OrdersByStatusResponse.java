package com.livebarn.sushi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record OrdersByStatusResponse(
        @JsonProperty("in-progress") List<OrderStatusEntry> inProgress,
        @JsonProperty("created") List<OrderStatusEntry> created,
        @JsonProperty("paused") List<OrderStatusEntry> paused,
        @JsonProperty("resumed") List<OrderStatusEntry> resumed,
        @JsonProperty("cancelled") List<OrderStatusEntry> cancelled,
        @JsonProperty("completed") List<OrderStatusEntry> completed
) {
}
