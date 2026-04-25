package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskCustomerProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface KioskCustomerProfileRepository extends JpaRepository<KioskCustomerProfileEntity, Long> {
    Optional<KioskCustomerProfileEntity> findByTaxId(String taxId);
}
