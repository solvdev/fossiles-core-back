package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleReturnLineEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OnlineSaleReturnLineRepository extends JpaRepository<OnlineSaleReturnLineEntity, Long> {
    List<OnlineSaleReturnLineEntity> findByReturnIdOrderByIdAsc(Long returnId);

    @Query("SELECT l FROM OnlineSaleReturnLineEntity l "
            + "WHERE l.returnId IN (SELECT r.id FROM OnlineSaleReturnEntity r WHERE r.onlineSaleId = :saleId)")
    List<OnlineSaleReturnLineEntity> findByOnlineSaleId(@Param("saleId") Long saleId);
}

