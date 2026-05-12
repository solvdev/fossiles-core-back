package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleReturnEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OnlineSaleReturnRepository extends JpaRepository<OnlineSaleReturnEntity, Long> {
    List<OnlineSaleReturnEntity> findByCreatedAtBetweenOrderByIdDesc(LocalDateTime start, LocalDateTime end);
    List<OnlineSaleReturnEntity> findAllByOrderByIdDesc();
}

