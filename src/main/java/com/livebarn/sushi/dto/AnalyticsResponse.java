package com.livebarn.sushi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record AnalyticsResponse(
        @JsonProperty("averageWaitTime") double averageWaitTime,
        @JsonProperty("averageMakeTime") double averageMakeTime,
        @JsonProperty("chefUtilization") double chefUtilization,
        @JsonProperty("mostPopularSushi") String mostPopularSushi,
        @JsonProperty("ordersByHour") Map<String, Integer> ordersByHour,
        @JsonProperty("code") int code,
        @JsonProperty("msg") String msg
) {
}
