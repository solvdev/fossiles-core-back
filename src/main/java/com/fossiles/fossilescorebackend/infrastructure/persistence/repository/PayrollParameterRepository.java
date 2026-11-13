package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollParameterEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/** Versiones de los parámetros legales de planilla. */
public interface PayrollParameterRepository extends JpaRepository<PayrollParameterEntity, Long> {
    List<PayrollParameterEntity> findByEffectiveFromLessThanEqualOrderByCodeAscEffectiveFromDesc(LocalDate date);
}
