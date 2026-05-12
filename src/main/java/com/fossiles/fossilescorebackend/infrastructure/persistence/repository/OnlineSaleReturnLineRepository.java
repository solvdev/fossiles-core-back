package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleReturnLineEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OnlineSaleReturnLineRepository extends JpaRepository<OnlineSaleReturnLineEntity, Long> {
    List<OnlineSaleReturnLineEntity> findByReturnIdOrderByIdAsc(Long returnId);
}

