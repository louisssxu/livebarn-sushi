package com.livebarn.sushi.service;

import com.livebarn.sushi.model.Chef;
import com.livebarn.sushi.model.OrderStatus;
import com.livebarn.sushi.model.Sushi;
import com.livebarn.sushi.model.SushiOrder;
import com.livebarn.sushi.repository.SushiOrderRepository;
import com.livebarn.sushi.repository.SushiRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class OrderProcessor {

    private static final Logger log = LoggerFactory.getLogger(OrderProcessor.class);

    private final List<Chef> chefs = IntStream.rangeClosed(1, Chef.COUNT)
            .mapToObj(Chef::new)
            .toList();

    private final LinkedList<Integer> pendingOrders = new LinkedList<>();
    private final Object lock = new Object();
    private int resumedInQueue = 0;
    private final ConcurrentHashMap<Integer, Integer> remainingSeconds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Thread> cookingThreads = new ConcurrentHashMap<>();
    private final SushiOrderRepository orderRepository;
    private final SushiRepository sushiRepository;
    private final TransactionTemplate transactionTemplate;
    private final AnalyticsService analyticsService;
    private ExecutorService executor;

    public OrderProcessor(
            SushiOrderRepository orderRepository,
            SushiRepository sushiRepository,
            TransactionTemplate transactionTemplate,
            AnalyticsService analyticsService
    ) {
        this.orderRepository = orderRepository;
        this.sushiRepository = sushiRepository;
        this.transactionTemplate = transactionTemplate;
        this.analyticsService = analyticsService;
    }

    @PostConstruct
    void startChefs() {
        executor = Executors.newFixedThreadPool(chefs.size());
        for (Chef chef : chefs) {
            executor.submit(() -> chefLoop(chef));
        }

        orderRepository.findByStatusIdOrderByCreatedAtAsc(OrderStatus.CREATED)
                .forEach(order -> enqueue(order.getId()));

        orderRepository.findByStatusIdOrderByCreatedAtAsc(OrderStatus.RESUMED)
                .forEach(order -> enqueuePriority(order.getId()));
    }

    @PreDestroy
    void stopChefs() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public void enqueue(int orderId) {
        synchronized (lock) {
            pendingOrders.addLast(orderId);
            lock.notify();
        }
    }

    public void enqueuePriority(int orderId) {
        synchronized (lock) {
            pendingOrders.add(resumedInQueue, orderId);
            resumedInQueue++;
            lock.notify();
        }
    }

    public void removeFromQueue(int orderId) {
        synchronized (lock) {
            int index = pendingOrders.indexOf(orderId);
            if (index >= 0) {
                pendingOrders.remove(index);
                if (index < resumedInQueue) {
                    resumedInQueue--;
                }
            }
        }
    }

    public boolean hasRemainingTime(int orderId) {
        Integer remaining = remainingSeconds.get(orderId);
        return remaining != null && remaining > 0;
    }

    public boolean pauseActive(int orderId) {
        Thread thread = cookingThreads.get(orderId);
        if (thread == null) {
            return false;
        }

        thread.interrupt();
        return true;
    }

    public boolean cancelActive(int orderId) {
        Thread thread = cookingThreads.get(orderId);
        if (thread == null) {
            return false;
        }

        thread.interrupt();
        return true;
    }

    public int getTimeSpent(int orderId, int statusId, Integer timeToMake) {
        return switch (statusId) {
            case OrderStatus.CREATED -> 0;
            case OrderStatus.FINISHED -> timeToMake != null ? timeToMake : 0;
            case OrderStatus.IN_PROGRESS, OrderStatus.PAUSED, OrderStatus.RESUMED, OrderStatus.CANCELLED ->
                    timeSpentFromRemaining(orderId, timeToMake);
            default -> 0;
        };
    }

    private int timeSpentFromRemaining(int orderId, Integer timeToMake) {
        if (timeToMake == null) {
            return 0;
        }

        Integer remaining = remainingSeconds.get(orderId);
        if (remaining == null) {
            return 0;
        }

        return Math.max(0, timeToMake - remaining);
    }

    private int takeNextOrder() throws InterruptedException {
        synchronized (lock) {
            while (pendingOrders.isEmpty()) {
                lock.wait();
            }
            int orderId = pendingOrders.removeFirst();
            if (resumedInQueue > 0) {
                resumedInQueue--;
            }
            return orderId;
        }
    }

    private void chefLoop(Chef chef) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                int orderId = takeNextOrder();
                processOrder(chef, orderId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void processOrder(Chef chef, int orderId) {
        Boolean shouldCook = transactionTemplate.execute(status -> prepareCooking(orderId));
        if (shouldCook == null || !shouldCook) {
            return;
        }

        analyticsService.chefBusyStart(chef.getId());
        cookingThreads.put(orderId, Thread.currentThread());
        log.info("Chef {} started order {} ({} seconds remaining)",
                chef.getId(), orderId, remainingSeconds.get(orderId));

        try {
            while (hasRemainingTime(orderId)) {
                TimeUnit.SECONDS.sleep(1);

                SushiOrder order = orderRepository.findById(orderId).orElse(null);
                if (order == null
                        || order.getStatusId() == OrderStatus.CANCELLED
                        || order.getStatusId() == OrderStatus.PAUSED) {
                    return;
                }

                remainingSeconds.computeIfPresent(orderId, (id, remaining) -> Math.max(0, remaining - 1));
            }

            transactionTemplate.executeWithoutResult(status -> {
                orderRepository.findById(orderId).ifPresent(order -> {
                    if (order.getStatusId() == OrderStatus.IN_PROGRESS) {
                        order.setStatusId(OrderStatus.FINISHED);
                        orderRepository.save(order);
                        remainingSeconds.remove(orderId);
                        analyticsService.recordOrderFinished(orderId);
                        log.info("Chef {} finished order {}", chef.getId(), orderId);
                    }
                });
            });
        } catch (InterruptedException e) {
            Thread.interrupted();
        } finally {
            analyticsService.chefBusyEnd(chef.getId());
            cookingThreads.remove(orderId);
        }
    }

    private Boolean prepareCooking(int orderId) {
        SushiOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return null;
        }

        if (order.getStatusId() == OrderStatus.RESUMED && hasRemainingTime(orderId)) {
            order.setStatusId(OrderStatus.IN_PROGRESS);
            orderRepository.save(order);
            return true;
        }

        if (order.getStatusId() == OrderStatus.CREATED) {
            Sushi sushi = sushiRepository.findById(order.getSushiId()).orElse(null);
            if (sushi == null || sushi.getTimeToMake() == null) {
                return null;
            }

            order.setStatusId(OrderStatus.IN_PROGRESS);
            orderRepository.save(order);
            remainingSeconds.put(orderId, sushi.getTimeToMake());
            analyticsService.recordCreatedToInProgress(orderId, order.getCreatedAt());
            return true;
        }

        return null;
    }
}
