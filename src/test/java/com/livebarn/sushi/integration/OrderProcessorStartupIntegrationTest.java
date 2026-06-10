package com.livebarn.sushi.integration;

import com.livebarn.sushi.model.OrderStatus;
import com.livebarn.sushi.model.SushiOrder;
import com.livebarn.sushi.repository.SushiOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "spring.sql.init.data-locations=classpath:startup-data.sql")
class OrderProcessorStartupIntegrationTest {

    @Autowired
    private SushiOrderRepository orderRepository;

    @Test
    void reEnqueuesCreatedOrdersOnStartup() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            SushiOrder order = orderRepository.findById(1).orElseThrow();
            if (order.getStatusId() == OrderStatus.FINISHED) {
                assertEquals(OrderStatus.FINISHED, order.getStatusId());
                return;
            }
            Thread.sleep(100);
        }

        SushiOrder order = orderRepository.findById(1).orElseThrow();
        assertEquals(OrderStatus.FINISHED, order.getStatusId(),
                "Pre-existing CREATED order should be picked up and finished on startup");
    }
}
