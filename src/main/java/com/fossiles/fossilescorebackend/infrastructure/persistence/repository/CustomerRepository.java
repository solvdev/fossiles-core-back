package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<CustomerEntity, Long> {
    /** Varios clientes pueden compartir el mismo NIT (mismo dueño, distintos negocios). */
    List<CustomerEntity> findByNit(String nit);

    boolean existsByLegacyCode(String legacyCode);

    Optional<CustomerEntity> findByLegacyCode(String legacyCode);
}
