package com.livebarn.sushi.integration;

import com.livebarn.sushi.model.OrderStatus;
import com.livebarn.sushi.model.SushiOrder;
import com.livebarn.sushi.repository.SushiOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Order lifecycle integration")
class OrderLifecycleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SushiOrderRepository orderRepository;

    private int createOrder(String sushiName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sushi_name\":\"" + sushiName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.order.id");
    }

    private void awaitStatus(int orderId, int expectedStatus, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            SushiOrder order = orderRepository.findById(orderId).orElseThrow();
            if (order.getStatusId() == expectedStatus) {
                return;
            }
            Thread.sleep(100);
        }
        SushiOrder order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(expectedStatus, order.getStatusId(),
                "Order " + orderId + " did not reach status " + expectedStatus + " within " + timeoutMs + "ms");
    }

    @Nested
    @DisplayName("create and finish")
    class CreateAndFinish {

        @Test
        void orderFinishesAfterChefCooks() throws Exception {
            int orderId = createOrder("California Roll");

            awaitStatus(orderId, OrderStatus.FINISHED, 5000);
        }

        @Test
        void analyticsReflectCompletedOrder() throws Exception {
            createOrder("California Roll");
            Thread.sleep(2500);

            mockMvc.perform(get("/api/orders/analytics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mostPopularSushi").value("California Roll"))
                    .andExpect(jsonPath("$.code").value(0));
        }
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        void cancelCreatedOrder() throws Exception {
            int orderId = createOrder("Kamikaze Roll");

            mockMvc.perform(delete("/api/orders/" + orderId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));

            awaitStatus(orderId, OrderStatus.CANCELLED, 2000);
        }

        @Test
        void cancelInProgressOrder() throws Exception {
            int orderId = createOrder("Kamikaze Roll");
            awaitStatus(orderId, OrderStatus.IN_PROGRESS, 5000);

            mockMvc.perform(delete("/api/orders/" + orderId))
                    .andExpect(status().isOk());

            awaitStatus(orderId, OrderStatus.CANCELLED, 3000);
        }
    }

    @Nested
    @DisplayName("pause and resume")
    class PauseAndResume {

        @Test
        void pauseResumeAndFinish() throws Exception {
            int orderId = createOrder("Kamikaze Roll");
            awaitStatus(orderId, OrderStatus.IN_PROGRESS, 5000);

            mockMvc.perform(put("/api/orders/" + orderId + "/pause"))
                    .andExpect(status().isOk());

            awaitStatus(orderId, OrderStatus.PAUSED, 3000);

            mockMvc.perform(put("/api/orders/" + orderId + "/resume"))
                    .andExpect(status().isOk());

            awaitStatus(orderId, OrderStatus.FINISHED, 8000);
        }
    }

    @Nested
    @DisplayName("status endpoint")
    class StatusEndpoint {

        @Test
        void returnsStatusBuckets() throws Exception {
            int orderId = createOrder("California Roll");

            MvcResult result = mockMvc.perform(get("/api/orders/status"))
                    .andExpect(status().isOk())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            assertTrue(body.contains("\"created\"") || body.contains("\"in-progress\"")
                    || body.contains("\"completed\""));
        }
    }
}
