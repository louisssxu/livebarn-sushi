package com.livebarn.sushi.service;

import com.livebarn.sushi.dto.OrderMapper;
import com.livebarn.sushi.dto.OrderResponse;
import com.livebarn.sushi.dto.OrderStatusEntry;
import com.livebarn.sushi.dto.OrdersByStatusResponse;
import com.livebarn.sushi.model.OrderStatus;
import com.livebarn.sushi.model.Sushi;
import com.livebarn.sushi.model.SushiOrder;
import com.livebarn.sushi.repository.SushiOrderRepository;
import com.livebarn.sushi.repository.SushiRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class OrderService {

    private final SushiRepository sushiRepository;
    private final SushiOrderRepository orderRepository;
    private final OrderProcessor orderProcessor;

    public OrderService(
            SushiRepository sushiRepository,
            SushiOrderRepository orderRepository,
            OrderProcessor orderProcessor
    ) {
        this.sushiRepository = sushiRepository;
        this.orderRepository = orderRepository;
        this.orderProcessor = orderProcessor;
    }

    @Transactional
    public Optional<OrderResponse> createOrder(String sushiName) {
        Optional<Sushi> sushi = sushiRepository.findByName(sushiName);
        if (sushi.isEmpty()) {
            return Optional.empty();
        }

        SushiOrder order = new SushiOrder();
        order.setStatusId(OrderStatus.CREATED);
        order.setSushiId(sushi.get().getId());
        order.setCreatedAt(new Timestamp(System.currentTimeMillis()));

        SushiOrder savedOrder = orderRepository.save(order);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                orderProcessor.enqueue(savedOrder.getId());
            }
        });

        return Optional.of(OrderMapper.toResponse(savedOrder));
    }

    @Transactional
    public OrderActionResult cancelOrder(int orderId) {
        SushiOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return OrderActionResult.NOT_FOUND;
        }

        int status = order.getStatusId();
        if (status == OrderStatus.FINISHED || status == OrderStatus.CANCELLED) {
            return OrderActionResult.INVALID_STATE;
        }

        if (status == OrderStatus.CREATED || status == OrderStatus.RESUMED) {
            orderProcessor.removeFromQueue(orderId);
        } else if (status == OrderStatus.IN_PROGRESS) {
            orderProcessor.cancelActive(orderId);
        }

        order.setStatusId(OrderStatus.CANCELLED);
        orderRepository.save(order);
        return OrderActionResult.SUCCESS;
    }

    @Transactional
    public OrderActionResult pauseOrder(int orderId) {
        SushiOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return OrderActionResult.NOT_FOUND;
        }

        if (order.getStatusId() != OrderStatus.IN_PROGRESS) {
            return OrderActionResult.INVALID_STATE;
        }

        if (!orderProcessor.pauseActive(orderId)) {
            return OrderActionResult.INVALID_STATE;
        }

        order.setStatusId(OrderStatus.PAUSED);
        orderRepository.save(order);
        return OrderActionResult.SUCCESS;
    }

    @Transactional
    public OrderActionResult resumeOrder(int orderId) {
        SushiOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return OrderActionResult.NOT_FOUND;
        }

        if (order.getStatusId() != OrderStatus.PAUSED || !orderProcessor.hasRemainingTime(orderId)) {
            return OrderActionResult.INVALID_STATE;
        }

        order.setStatusId(OrderStatus.RESUMED);
        orderRepository.save(order);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                orderProcessor.enqueuePriority(orderId);
            }
        });

        return OrderActionResult.SUCCESS;
    }

    public OrdersByStatusResponse getOrdersByStatus() {
        List<OrderStatusEntry> inProgress = new ArrayList<>();
        List<OrderStatusEntry> created = new ArrayList<>();
        List<OrderStatusEntry> paused = new ArrayList<>();
        List<OrderStatusEntry> resumed = new ArrayList<>();
        List<OrderStatusEntry> cancelled = new ArrayList<>();
        List<OrderStatusEntry> completed = new ArrayList<>();

        for (SushiOrder order : orderRepository.findAll()) {
            Integer timeToMake = sushiRepository.findById(order.getSushiId())
                    .map(Sushi::getTimeToMake)
                    .orElse(null);
            int timeSpent = orderProcessor.getTimeSpent(order.getId(), order.getStatusId(), timeToMake);
            OrderStatusEntry entry = new OrderStatusEntry(order.getId(), timeSpent);

            switch (order.getStatusId()) {
                case OrderStatus.IN_PROGRESS -> inProgress.add(entry);
                case OrderStatus.CREATED -> created.add(entry);
                case OrderStatus.PAUSED -> paused.add(entry);
                case OrderStatus.RESUMED -> resumed.add(entry);
                case OrderStatus.CANCELLED -> cancelled.add(entry);
                case OrderStatus.FINISHED -> completed.add(entry);
                default -> {
                }
            }
        }

        return new OrdersByStatusResponse(inProgress, created, paused, resumed, cancelled, completed);
    }
}
