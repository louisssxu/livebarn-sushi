package com.livebarn.sushi.controller;

import com.livebarn.sushi.dto.AnalyticsResponse;
import com.livebarn.sushi.dto.OrderResponse;
import com.livebarn.sushi.dto.OrdersByStatusResponse;
import com.livebarn.sushi.service.OrderActionResult;
import com.livebarn.sushi.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@DisplayName("OrderController")
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Nested
    @DisplayName("POST /api/orders")
    class CreateOrder {

        @Test
        void returns201WhenOrderCreated() throws Exception {
            OrderResponse order = new OrderResponse(1, 1, 1, new Timestamp(System.currentTimeMillis()));
            when(orderService.createOrder("California Roll")).thenReturn(Optional.of(order));

            mockMvc.perform(post("/api/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sushi_name\":\"California Roll\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.msg").value("Order created"))
                    .andExpect(jsonPath("$.order.id").value(1));
        }

        @Test
        void returns400WhenSushiNotFound() throws Exception {
            when(orderService.createOrder("Unknown")).thenReturn(Optional.empty());

            mockMvc.perform(post("/api/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sushi_name\":\"Unknown\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(1))
                    .andExpect(jsonPath("$.msg").value("Sushi not found"));
        }
    }

    @Nested
    @DisplayName("GET /api/orders/status")
    class GetOrdersByStatus {

        @Test
        void returnsGroupedOrders() throws Exception {
            OrdersByStatusResponse response = new OrdersByStatusResponse(
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
            );
            when(orderService.getOrdersByStatus()).thenReturn(response);

            mockMvc.perform(get("/api/orders/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.created").isArray());

            verify(orderService).getOrdersByStatus();
        }
    }

    @Nested
    @DisplayName("GET /api/orders/analytics")
    class GetAnalytics {

        @Test
        void returnsAnalytics() throws Exception {
            AnalyticsResponse response = new AnalyticsResponse(
                    1.5, 2.0, 0.33, "California Roll", Map.of("14", 3), 0, "Analytics retrieved"
            );
            when(orderService.getAnalytics()).thenReturn(response);

            mockMvc.perform(get("/api/orders/analytics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.averageWaitTime").value(1.5))
                    .andExpect(jsonPath("$.mostPopularSushi").value("California Roll"))
                    .andExpect(jsonPath("$.msg").value("Analytics retrieved"));
        }
    }

    @Nested
    @DisplayName("DELETE /api/orders/{orderId}")
    class CancelOrder {

        @Test
        void returns200OnSuccess() throws Exception {
            when(orderService.cancelOrder(1)).thenReturn(OrderActionResult.SUCCESS);

            mockMvc.perform(delete("/api/orders/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.msg").value("Order cancelled"));
        }

        @Test
        void returns404WhenNotFound() throws Exception {
            when(orderService.cancelOrder(1)).thenReturn(OrderActionResult.NOT_FOUND);

            mockMvc.perform(delete("/api/orders/1"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(1))
                    .andExpect(jsonPath("$.msg").value("Order not found"));
        }

        @Test
        void returns400OnInvalidState() throws Exception {
            when(orderService.cancelOrder(1)).thenReturn(OrderActionResult.INVALID_STATE);

            mockMvc.perform(delete("/api/orders/1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(2))
                    .andExpect(jsonPath("$.msg").value("Invalid order state"));
        }
    }

    @Nested
    @DisplayName("PUT /api/orders/{orderId}/pause")
    class PauseOrder {

        @Test
        void returns200OnSuccess() throws Exception {
            when(orderService.pauseOrder(1)).thenReturn(OrderActionResult.SUCCESS);

            mockMvc.perform(put("/api/orders/1/pause"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.msg").value("Order paused"));
        }

        @Test
        void returns404WhenNotFound() throws Exception {
            when(orderService.pauseOrder(1)).thenReturn(OrderActionResult.NOT_FOUND);

            mockMvc.perform(put("/api/orders/1/pause"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.msg").value("Order not found"));
        }

        @Test
        void returns400OnInvalidState() throws Exception {
            when(orderService.pauseOrder(1)).thenReturn(OrderActionResult.INVALID_STATE);

            mockMvc.perform(put("/api/orders/1/pause"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(2));
        }
    }

    @Nested
    @DisplayName("PUT /api/orders/{orderId}/resume")
    class ResumeOrder {

        @Test
        void returns200OnSuccess() throws Exception {
            when(orderService.resumeOrder(1)).thenReturn(OrderActionResult.SUCCESS);

            mockMvc.perform(put("/api/orders/1/resume"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.msg").value("Order resumed"));
        }

        @Test
        void returns404WhenNotFound() throws Exception {
            when(orderService.resumeOrder(1)).thenReturn(OrderActionResult.NOT_FOUND);

            mockMvc.perform(put("/api/orders/1/resume"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.msg").value("Order not found"));
        }

        @Test
        void returns400OnInvalidState() throws Exception {
            when(orderService.resumeOrder(1)).thenReturn(OrderActionResult.INVALID_STATE);

            mockMvc.perform(put("/api/orders/1/resume"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(2));
        }
    }
}
