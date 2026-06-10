package com.livebarn.sushi.repository;

import com.livebarn.sushi.model.SushiOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SushiOrderRepository extends JpaRepository<SushiOrder, Integer> {
    List<SushiOrder> findByStatusIdOrderByCreatedAtAsc(Integer statusId);
}

