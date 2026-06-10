package com.livebarn.sushi.service;

import com.livebarn.sushi.model.Chef;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.springframework.transaction.TransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderProcessor} public behavior.
 * Does not invoke {@code @PostConstruct startChefs()} or test the cooking loop.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderProcessor")
class OrderProcessorTest {

    @Mock
    private SushiOrderRepository orderRepository;

    @Mock
    private SushiRepository sushiRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private AnalyticsService analyticsService;

    private OrderProcessor orderProcessor;

    @BeforeEach
    void setUp() {
        orderProcessor = new OrderProcessor(
                orderRepository,
                sushiRepository,
                transactionTemplate,
                analyticsService
        );
    }

    @SuppressWarnings("unchecked")
    private LinkedList<Integer> pendingOrders() {
        return (LinkedList<Integer>) ReflectionTestUtils.getField(orderProcessor, "pendingOrders");
    }

    private int resumedInQueue() {
        return (int) ReflectionTestUtils.getField(orderProcessor, "resumedInQueue");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<Integer, Integer> remainingSeconds() {
        return (ConcurrentHashMap<Integer, Integer>) ReflectionTestUtils.getField(orderProcessor, "remainingSeconds");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<Integer, Thread> cookingThreads() {
        return (ConcurrentHashMap<Integer, Thread>) ReflectionTestUtils.getField(orderProcessor, "cookingThreads");
    }

    @Nested
    @DisplayName("enqueue / enqueuePriority / removeFromQueue")
    class QueueOperations {

        @Test
        void enqueueAppendsToTail() {
            orderProcessor.enqueue(1);
            orderProcessor.enqueue(2);

            assertEquals(List.of(1, 2), pendingOrders());
        }

        @Test
        void enqueuePriorityInsertsAtFront() {
            orderProcessor.enqueue(10);
            orderProcessor.enqueue(20);
            orderProcessor.enqueuePriority(15);

            assertEquals(List.of(15, 10, 20), pendingOrders());
            assertEquals(1, resumedInQueue());
        }

        @Test
        void multiplePriorityInsertsPreserveOrder() {
            orderProcessor.enqueue(10);
            orderProcessor.enqueue(20);
            orderProcessor.enqueuePriority(15);
            orderProcessor.enqueuePriority(12);

            assertEquals(List.of(15, 12, 10, 20), pendingOrders());
            assertEquals(2, resumedInQueue());
        }

        @Test
        void removeFromQueueRemovesExisting() {
            orderProcessor.enqueue(1);
            orderProcessor.enqueue(2);
            orderProcessor.enqueue(3);

            orderProcessor.removeFromQueue(2);

            assertEquals(List.of(1, 3), pendingOrders());
        }

        @Test
        void removeFromQueueDecrementsResumedCounterWhenRemovingPriority() {
            orderProcessor.enqueue(10);
            orderProcessor.enqueuePriority(15);
            orderProcessor.enqueuePriority(12);
            assertEquals(2, resumedInQueue());

            orderProcessor.removeFromQueue(12);

            assertEquals(List.of(15, 10), pendingOrders());
            assertEquals(1, resumedInQueue());
        }

        @Test
        void removeFromQueueNoOpWhenAbsent() {
            orderProcessor.enqueue(1);

            orderProcessor.removeFromQueue(99);

            assertEquals(List.of(1), pendingOrders());
        }

        @Test
        void removeFromQueueNonPriorityDoesNotDecrementResumedCounter() {
            orderProcessor.enqueuePriority(15);
            orderProcessor.enqueue(10);
            assertEquals(1, resumedInQueue());

            orderProcessor.removeFromQueue(10);

            assertEquals(List.of(15), pendingOrders());
            assertEquals(1, resumedInQueue());
        }
    }

    @Nested
    @DisplayName("hasRemainingTime")
    class HasRemainingTime {

        @Test
        void trueWhenRemainingPositive() {
            remainingSeconds().put(1, 5);

            assertTrue(orderProcessor.hasRemainingTime(1));
        }

        @Test
        void falseWhenMissing() {
            assertFalse(orderProcessor.hasRemainingTime(1));
        }

        @Test
        void falseWhenZero() {
            remainingSeconds().put(1, 0);

            assertFalse(orderProcessor.hasRemainingTime(1));
        }
    }

    @Nested
    @DisplayName("getTimeSpent")
    class GetTimeSpent {

        @Test
        void createdReturnsZero() {
            assertEquals(0, orderProcessor.getTimeSpent(1, OrderStatus.CREATED, 30));
        }

        @Test
        void finishedReturnsTimeToMake() {
            assertEquals(30, orderProcessor.getTimeSpent(1, OrderStatus.FINISHED, 30));
        }

        @Test
        void finishedReturnsZeroWhenTimeToMakeNull() {
            assertEquals(0, orderProcessor.getTimeSpent(1, OrderStatus.FINISHED, null));
        }

        @Test
        void inProgressUsesRemaining() {
            remainingSeconds().put(1, 20);

            assertEquals(10, orderProcessor.getTimeSpent(1, OrderStatus.IN_PROGRESS, 30));
        }

        @Test
        void pausedResumedCancelledUseRemaining() {
            remainingSeconds().put(1, 15);

            assertEquals(15, orderProcessor.getTimeSpent(1, OrderStatus.PAUSED, 30));
            assertEquals(15, orderProcessor.getTimeSpent(1, OrderStatus.RESUMED, 30));
            assertEquals(15, orderProcessor.getTimeSpent(1, OrderStatus.CANCELLED, 30));
        }

        @Test
        void returnsZeroWhenRemainingMissing() {
            assertEquals(0, orderProcessor.getTimeSpent(1, OrderStatus.IN_PROGRESS, 30));
        }

        @Test
        void returnsZeroWhenTimeToMakeNullForActiveStatuses() {
            remainingSeconds().put(1, 10);

            assertEquals(0, orderProcessor.getTimeSpent(1, OrderStatus.IN_PROGRESS, null));
        }

        @Test
        void unknownStatusReturnsZero() {
            remainingSeconds().put(1, 10);

            assertEquals(0, orderProcessor.getTimeSpent(1, 99, 30));
        }
    }

    @Nested
    @DisplayName("pauseActive / cancelActive")
    class ActiveOrderControl {

        @Test
        void pauseActiveReturnsFalseWhenNoCookingThread() {
            assertFalse(orderProcessor.pauseActive(1));
        }

        @Test
        void cancelActiveReturnsFalseWhenNoCookingThread() {
            assertFalse(orderProcessor.cancelActive(1));
        }

        @Test
        void pauseActiveReturnsTrueWhenThreadRegistered() {
            cookingThreads().put(1, Thread.currentThread());

            assertTrue(orderProcessor.pauseActive(1));
        }

        @Test
        void cancelActiveReturnsTrueWhenThreadRegistered() {
            cookingThreads().put(1, Thread.currentThread());

            assertTrue(orderProcessor.cancelActive(1));
        }
    }

    @Nested
    @DisplayName("takeNextOrder")
    class TakeNextOrder {

        @Test
        void decrementsResumedInQueueWhenTakingPriorityOrder() throws Exception {
            orderProcessor.enqueue(10);
            orderProcessor.enqueuePriority(15);
            assertEquals(1, resumedInQueue());

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> taken = executor.submit(() ->
                        ReflectionTestUtils.invokeMethod(orderProcessor, "takeNextOrder"));

                assertEquals(15, taken.get(2, TimeUnit.SECONDS));
                assertEquals(0, resumedInQueue());
                assertEquals(List.of(10), pendingOrders());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("processOrder")
    class ProcessOrder {

        @Test
        void cooksCreatedOrderAndRecordsAnalytics() throws Exception {
            stubTransactionTemplate();

            SushiOrder order = createdOrder(1, 1);
            Sushi sushi = sushi(1, 1);
            AtomicReference<SushiOrder> orderRef = new AtomicReference<>(order);

            when(orderRepository.findById(1)).thenAnswer(invocation -> Optional.of(orderRef.get()));
            when(orderRepository.save(any(SushiOrder.class))).thenAnswer(invocation -> {
                SushiOrder saved = invocation.getArgument(0);
                orderRef.set(saved);
                return saved;
            });
            when(sushiRepository.findById(1)).thenReturn(Optional.of(sushi));

            ReflectionTestUtils.invokeMethod(orderProcessor, "processOrder", new Chef(1), 1);

            assertEquals(OrderStatus.FINISHED, orderRef.get().getStatusId());
            verify(analyticsService).recordCreatedToInProgress(1, order.getCreatedAt());
            verify(analyticsService).chefBusyStart(1);
            verify(analyticsService).recordOrderFinished(1);
            verify(analyticsService).chefBusyEnd(1);
        }

        @Test
        void resumesPausedOrderWithoutRecordingWaitTimeAgain() throws Exception {
            stubTransactionTemplate();

            SushiOrder order = new SushiOrder();
            ReflectionTestUtils.setField(order, "id", 1);
            order.setStatusId(OrderStatus.RESUMED);
            order.setSushiId(1);
            order.setCreatedAt(new Timestamp(System.currentTimeMillis()));

            AtomicReference<SushiOrder> orderRef = new AtomicReference<>(order);
            remainingSeconds().put(1, 1);

            when(orderRepository.findById(1)).thenAnswer(invocation -> Optional.of(orderRef.get()));
            when(orderRepository.save(any(SushiOrder.class))).thenAnswer(invocation -> {
                SushiOrder saved = invocation.getArgument(0);
                orderRef.set(saved);
                return saved;
            });

            ReflectionTestUtils.invokeMethod(orderProcessor, "processOrder", new Chef(1), 1);

            assertEquals(OrderStatus.FINISHED, orderRef.get().getStatusId());
            verify(analyticsService).chefBusyStart(1);
            verify(analyticsService).recordOrderFinished(1);
            verify(analyticsService).chefBusyEnd(1);
        }

        private void stubTransactionTemplate() {
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(null);
            });
            doAnswer(invocation -> {
                Consumer<TransactionStatus> callback = invocation.getArgument(0);
                callback.accept(null);
                return null;
            }).when(transactionTemplate).executeWithoutResult(any());
        }

        private static SushiOrder createdOrder(int orderId, int sushiId) {
            SushiOrder order = new SushiOrder();
            ReflectionTestUtils.setField(order, "id", orderId);
            order.setStatusId(OrderStatus.CREATED);
            order.setSushiId(sushiId);
            order.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            return order;
        }

        private static Sushi sushi(int id, int timeToMake) {
            Sushi sushi = new Sushi();
            ReflectionTestUtils.setField(sushi, "id", id);
            ReflectionTestUtils.setField(sushi, "timeToMake", timeToMake);
            return sushi;
        }
    }
}
