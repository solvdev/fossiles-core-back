package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeSalaryHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Historial de salario y bonificación de cada colaborador. */
public interface EmployeeSalaryHistoryRepository extends JpaRepository<EmployeeSalaryHistoryEntity, Long> {
    List<EmployeeSalaryHistoryEntity> findByEmployeeIdOrderByEffectiveFromDescIdDesc(Long employeeId);
}
