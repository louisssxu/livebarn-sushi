package com.livebarn.sushi.repository;

import com.livebarn.sushi.model.Sushi;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SushiRepository extends JpaRepository<Sushi, Integer> {
    Optional<Sushi> findByName(String name);
}
