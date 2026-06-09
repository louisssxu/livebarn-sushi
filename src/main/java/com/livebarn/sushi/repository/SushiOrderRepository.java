package com.livebarn.sushi.repository;

import com.livebarn.sushi.model.SushiOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SushiOrderRepository extends JpaRepository<SushiOrder, Integer> {}
