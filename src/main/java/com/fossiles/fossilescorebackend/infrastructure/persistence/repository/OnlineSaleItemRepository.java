package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface OnlineSaleItemRepository extends JpaRepository<OnlineSaleItemEntity, Long> {

    List<OnlineSaleItemEntity> findByOnlineSaleIdOrderByIdAsc(Long onlineSaleId);

    List<OnlineSaleItemEntity> findByOnlineSaleIdInOrderByIdAsc(java.util.Collection<Long> onlineSaleIds);

    @Modifying
    @Transactional
    @Query("DELETE FROM OnlineSaleItemEntity e WHERE e.onlineSaleId = :saleId")
    void deleteByOnlineSaleId(@Param("saleId") Long saleId);
}

