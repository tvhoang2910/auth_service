package com.exam_bank.auth_service.repository;

import com.exam_bank.auth_service.entity.PremiumPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PremiumPlanRepository extends JpaRepository<PremiumPlan, Long> {

    List<PremiumPlan> findByActiveTrueOrderByPriceAsc();

    List<PremiumPlan> findAllByOrderByCreatedAtDesc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}