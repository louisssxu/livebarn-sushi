package com.livebarn.sushi.controller;


import com.livebarn.sushi.model.Sushi;
import com.livebarn.sushi.model.SushiOrder;
import com.livebarn.sushi.repository.SushiOrderRepository;
import com.livebarn.sushi.repository.SushiRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final SushiRepository sushiRepository;
    private final SushiOrderRepository orderRepository;

    public OrderController(SushiRepository sushiRepository, SushiOrderRepository sushiOrderRepository){
        this.sushiRepository = sushiRepository;
        this.orderRepository = sushiOrderRepository;
    }

    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody Map<String, String> body){
        String sushiName = body.get("sushi_name");
        Sushi sushi = sushiRepository.findByName(sushiName).orElse(null);

        if (sushi == null){
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 1,
                    "msg", "Sushi not found"
            ));
        }

        SushiOrder order = new SushiOrder();


        order.setStatusId(1);
        order.setSushiId(sushi.getId());
        order.setCreatedAt(new Timestamp(System.currentTimeMillis()));


        SushiOrder savedOrder = orderRepository.save(order);
        return ResponseEntity.status(201).body(Map.of(
                "order", savedOrder,
                "code", 0,
                "msg", "Order created"
        ));

    }

}
