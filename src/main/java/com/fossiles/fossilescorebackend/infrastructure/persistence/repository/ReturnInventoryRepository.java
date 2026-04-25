package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ReturnInventoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ReturnInventoryRepository extends JpaRepository<ReturnInventoryEntity, Long> {

    List<ReturnInventoryEntity> findByOnlineSaleId(Long onlineSaleId);

    List<ReturnInventoryEntity> findByReturnDateBetweenOrderByReturnDateDesc(LocalDate start, LocalDate end);

    List<ReturnInventoryEntity> findAllByOrderByReturnDateDesc();

    boolean existsByOnlineSaleId(Long onlineSaleId);
}

