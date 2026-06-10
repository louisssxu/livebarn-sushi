package com.livebarn.sushi.service;

import com.livebarn.sushi.dto.AnalyticsResponse;
import com.livebarn.sushi.dto.OrderStatusEntry;
import com.livebarn.sushi.dto.OrdersByStatusResponse;
import com.livebarn.sushi.model.OrderStatus;
import com.livebarn.sushi.model.Sushi;
import com.livebarn.sushi.model.SushiOrder;
import com.livebarn.sushi.repository.SushiOrderRepository;
import com.livebarn.sushi.repository.SushiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService")
class OrderServiceTest {

    @Mock
    private SushiRepository sushiRepository;

    @Mock
    private SushiOrderRepository orderRepository;

    @Mock
    private OrderProcessor orderProcessor;

    @Mock
    private AnalyticsService analyticsService;

    @InjectMocks
    private OrderService orderService;

    private void runWithTransaction(Runnable action) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            action.run();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    private static Sushi sushi(int id, String name, int timeToMake) {
        Sushi sushi = new Sushi();
        ReflectionTestUtils.setField(sushi, "id", id);
        ReflectionTestUtils.setField(sushi, "name", name);
        ReflectionTestUtils.setField(sushi, "timeToMake", timeToMake);
        return sushi;
    }

    private static SushiOrder order(int id, int statusId, int sushiId) {
        SushiOrder order = new SushiOrder();
        ReflectionTestUtils.setField(order, "id", id);
        order.setStatusId(statusId);
        order.setSushiId(sushiId);
        return order;
    }

    @Nested
    @DisplayName("createOrder")
    class CreateOrder {

        @Test
        void returnsEmptyWhenSushiNotFound() {
            when(sushiRepository.findByName("Unknown")).thenReturn(Optional.empty());

            assertTrue(orderService.createOrder("Unknown").isEmpty());

            verify(orderRepository, never()).save(any());
            verifyNoInteractions(analyticsService, orderProcessor);
        }

        @Test
        void createsOrderAndRecordsAnalytics() {
            when(sushiRepository.findByName("Salmon")).thenReturn(Optional.of(sushi(1, "Salmon", 30)));
            when(orderRepository.save(any(SushiOrder.class))).thenAnswer(invocation -> {
                SushiOrder saved = invocation.getArgument(0);
                ReflectionTestUtils.setField(saved, "id", 42);
                saved.setCreatedAt(new Timestamp(System.currentTimeMillis()));
                return saved;
            });

            runWithTransaction(() -> orderService.createOrder("Salmon"));

            ArgumentCaptor<SushiOrder> orderCaptor = ArgumentCaptor.forClass(SushiOrder.class);
            verify(orderRepository).save(orderCaptor.capture());
            assertEquals(OrderStatus.CREATED, orderCaptor.getValue().getStatusId());
            assertEquals(1, orderCaptor.getValue().getSushiId());

            ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
            verify(analyticsService).recordOrderCreated(nameCaptor.capture(), any(Timestamp.class));
            assertEquals("Salmon", nameCaptor.getValue());
        }

        @Test
        void enqueuesAfterCommit() {
            when(sushiRepository.findByName("Salmon")).thenReturn(Optional.of(sushi(1, "Salmon", 30)));
            when(orderRepository.save(any(SushiOrder.class))).thenAnswer(invocation -> {
                SushiOrder saved = invocation.getArgument(0);
                ReflectionTestUtils.setField(saved, "id", 42);
                saved.setCreatedAt(new Timestamp(System.currentTimeMillis()));
                return saved;
            });

            runWithTransaction(() -> orderService.createOrder("Salmon"));

            verify(orderProcessor).enqueue(42);
        }
    }

    @Nested
    @DisplayName("cancelOrder")
    class CancelOrder {

        @Test
        void returnsNotFoundWhenMissing() {
            when(orderRepository.findById(1)).thenReturn(Optional.empty());

            assertEquals(OrderActionResult.NOT_FOUND, orderService.cancelOrder(1));
        }

        @Test
        void returnsInvalidStateWhenFinished() {
            when(orderRepository.findById(1)).thenReturn(Optional.of(order(1, OrderStatus.FINISHED, 1)));

            assertEquals(OrderActionResult.INVALID_STATE, orderService.cancelOrder(1));

            verify(orderRepository, never()).save(any());
        }

        @Test
        void returnsInvalidStateWhenCancelled() {
            when(orderRepository.findById(1)).thenReturn(Optional.of(order(1, OrderStatus.CANCELLED, 1)));

            assertEquals(OrderActionResult.INVALID_STATE, orderService.cancelOrder(1));

            verify(orderRepository, never()).save(any());
        }

        @Test
        void removesFromQueueWhenCreated() {
            SushiOrder existing = order(1, OrderStatus.CREATED, 1);
            when(orderRepository.findById(1)).thenReturn(Optional.of(existing));

            assertEquals(OrderActionResult.SUCCESS, orderService.cancelOrder(1));

            verify(orderProcessor).removeFromQueue(1);
            assertEquals(OrderStatus.CANCELLED, existing.getStatusId());
            verify(orderRepository).save(existing);
            verify(analyticsService).recordOrderCancelled(1);
        }

        @Test
        void removesFromQueueWhenResumed() {
            SushiOrder existing = order(1, OrderStatus.RESUMED, 1);
            when(orderRepository.findById(1)).thenReturn(Optional.of(existing));

            assertEquals(OrderActionResult.SUCCESS, orderService.cancelOrder(1));

            verify(orderProcessor).removeFromQueue(1);
        }

        @Test
        void cancelsActiveWhenInProgress() {
            SushiOrder existing = order(1, OrderStatus.IN_PROGRESS, 1);
            when(orderRepository.findById(1)).thenReturn(Optional.of(existing));

            assertEquals(OrderActionResult.SUCCESS, orderService.cancelOrder(1));

            verify(orderProcessor).cancelActive(1);
            verify(orderProcessor, never()).removeFromQueue(1);
            assertEquals(OrderStatus.CANCELLED, existing.getStatusId());
            verify(analyticsService).recordOrderCancelled(1);
        }

        @Test
        void successWhenPausedWithoutQueueOrActiveCancellation() {
            SushiOrder existing = order(1, OrderStatus.PAUSED, 1);
            when(orderRepository.findById(1)).thenReturn(Optional.of(existing));

            assertEquals(OrderActionResult.SUCCESS, orderService.cancelOrder(1));

            verify(orderProcessor, never()).removeFromQueue(1);
            verify(orderProcessor, never()).cancelActive(1);
            assertEquals(OrderStatus.CANCELLED, existing.getStatusId());
            verify(analyticsService).recordOrderCancelled(1);
        }
    }

    @Nested
    @DisplayName("pauseOrder")
    class PauseOrder {

        @Test
        void returnsNotFoundWhenMissing() {
            when(orderRepository.findById(1)).thenReturn(Optional.empty());

            assertEquals(OrderActionResult.NOT_FOUND, orderService.pauseOrder(1));
        }

        @Test
        void returnsInvalidStateWhenNotInProgress() {
            when(orderRepository.findById(1)).thenReturn(Optional.of(order(1, OrderStatus.CREATED, 1)));

            assertEquals(OrderActionResult.INVALID_STATE, orderService.pauseOrder(1));

            verify(orderProcessor, never()).pauseActive(1);
        }

        @Test
        void returnsInvalidStateWhenPauseActiveFails() {
            SushiOrder existing = order(1, OrderStatus.IN_PROGRESS, 1);
            when(orderRepository.findById(1)).thenReturn(Optional.of(existing));
            when(orderProcessor.pauseActive(1)).thenReturn(false);

            assertEquals(OrderActionResult.INVALID_STATE, orderService.pauseOrder(1));

            assertEquals(OrderStatus.IN_PROGRESS, existing.getStatusId());
            verify(orderRepository, never()).save(any());
        }

        @Test
        void successPausesAndRecordsAnalytics() {
            SushiOrder existing = order(1, OrderStatus.IN_PROGRESS, 1);
            when(orderRepository.findById(1)).thenReturn(Optional.of(existing));
            when(orderProcessor.pauseActive(1)).thenReturn(true);

            assertEquals(OrderActionResult.SUCCESS, orderService.pauseOrder(1));

            assertEquals(OrderStatus.PAUSED, existing.getStatusId());
            verify(analyticsService).recordPaused(1, OrderStatus.IN_PROGRESS);
            verify(orderRepository).save(existing);
        }
    }

    @Nested
    @DisplayName("resumeOrder")
    class ResumeOrder {

        @Test
        void returnsNotFoundWhenMissing() {
            when(orderRepository.findById(1)).thenReturn(Optional.empty());

            assertEquals(OrderActionResult.NOT_FOUND, orderService.resumeOrder(1));
        }

        @Test
        void returnsInvalidStateWhenNotPaused() {
            when(orderRepository.findById(1)).thenReturn(Optional.of(order(1, OrderStatus.CREATED, 1)));

            assertEquals(OrderActionResult.INVALID_STATE, orderService.resumeOrder(1));
        }

        @Test
        void returnsInvalidStateWhenNoRemainingTime() {
            when(orderRepository.findById(1)).thenReturn(Optional.of(order(1, OrderStatus.PAUSED, 1)));
            when(orderProcessor.hasRemainingTime(1)).thenReturn(false);

            assertEquals(OrderActionResult.INVALID_STATE, orderService.resumeOrder(1));
        }

        @Test
        void successResumesAndEnqueuesPriorityAfterCommit() {
            SushiOrder existing = order(1, OrderStatus.PAUSED, 1);
            when(orderRepository.findById(1)).thenReturn(Optional.of(existing));
            when(orderProcessor.hasRemainingTime(1)).thenReturn(true);

            runWithTransaction(() -> orderService.resumeOrder(1));

            assertEquals(OrderStatus.RESUMED, existing.getStatusId());
            verify(orderRepository).save(existing);
            verify(orderProcessor).enqueuePriority(1);
        }
    }

    @Nested
    @DisplayName("getOrdersByStatus")
    class GetOrdersByStatus {

        @Test
        void groupsOrdersByStatus() {
            SushiOrder inProgress = order(1, OrderStatus.IN_PROGRESS, 10);
            SushiOrder created = order(2, OrderStatus.CREATED, 10);
            SushiOrder paused = order(3, OrderStatus.PAUSED, 10);
            SushiOrder resumed = order(4, OrderStatus.RESUMED, 10);
            SushiOrder cancelled = order(5, OrderStatus.CANCELLED, 10);
            SushiOrder finished = order(6, OrderStatus.FINISHED, 10);

            when(orderRepository.findAll()).thenReturn(List.of(
                    inProgress, created, paused, resumed, cancelled, finished
            ));
            when(sushiRepository.findById(10)).thenReturn(Optional.of(sushi(10, "Salmon", 30)));
            when(orderProcessor.getTimeSpent(anyInt(), anyInt(), eq(30))).thenReturn(0);

            OrdersByStatusResponse response = orderService.getOrdersByStatus();

            assertEquals(List.of(1), response.inProgress().stream().map(OrderStatusEntry::orderId).toList());
            assertEquals(List.of(2), response.created().stream().map(OrderStatusEntry::orderId).toList());
            assertEquals(List.of(3), response.paused().stream().map(OrderStatusEntry::orderId).toList());
            assertEquals(List.of(4), response.resumed().stream().map(OrderStatusEntry::orderId).toList());
            assertEquals(List.of(5), response.cancelled().stream().map(OrderStatusEntry::orderId).toList());
            assertEquals(List.of(6), response.completed().stream().map(OrderStatusEntry::orderId).toList());
        }

        @Test
        void computesTimeSpentViaProcessor() {
            SushiOrder inProgress = order(1, OrderStatus.IN_PROGRESS, 10);
            when(orderRepository.findAll()).thenReturn(List.of(inProgress));
            when(sushiRepository.findById(10)).thenReturn(Optional.of(sushi(10, "Salmon", 30)));
            when(orderProcessor.getTimeSpent(1, OrderStatus.IN_PROGRESS, 30)).thenReturn(12);

            OrdersByStatusResponse response = orderService.getOrdersByStatus();

            assertEquals(12, response.inProgress().get(0).timeSpent());
            verify(orderProcessor).getTimeSpent(1, OrderStatus.IN_PROGRESS, 30);
        }

        @Test
        void usesNullTimeToMakeWhenSushiMissing() {
            SushiOrder inProgress = order(1, OrderStatus.IN_PROGRESS, 99);
            when(orderRepository.findAll()).thenReturn(List.of(inProgress));
            when(sushiRepository.findById(99)).thenReturn(Optional.empty());
            when(orderProcessor.getTimeSpent(1, OrderStatus.IN_PROGRESS, null)).thenReturn(0);

            OrdersByStatusResponse response = orderService.getOrdersByStatus();

            assertEquals(0, response.inProgress().get(0).timeSpent());
            verify(orderProcessor).getTimeSpent(1, OrderStatus.IN_PROGRESS, null);
        }

        @Test
        void ignoresUnknownStatus() {
            SushiOrder unknown = order(1, 99, 10);
            when(orderRepository.findAll()).thenReturn(List.of(unknown));
            when(sushiRepository.findById(10)).thenReturn(Optional.of(sushi(10, "Salmon", 30)));
            when(orderProcessor.getTimeSpent(1, 99, 30)).thenReturn(0);

            OrdersByStatusResponse response = orderService.getOrdersByStatus();

            assertTrue(response.inProgress().isEmpty());
            assertTrue(response.created().isEmpty());
            assertTrue(response.paused().isEmpty());
            assertTrue(response.resumed().isEmpty());
            assertTrue(response.cancelled().isEmpty());
            assertTrue(response.completed().isEmpty());
        }
    }

    @Nested
    @DisplayName("getAnalytics")
    class GetAnalytics {

        @Test
        void delegatesToAnalyticsService() {
            AnalyticsResponse expected = new AnalyticsResponse(0, 0, 0, null, java.util.Map.of(), 0, "Analytics retrieved");
            when(analyticsService.getAnalytics()).thenReturn(expected);

            assertEquals(expected, orderService.getAnalytics());
        }
    }
}
