package com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconSellerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReconSellerRepository extends JpaRepository<ReconSellerEntity, Long> {
    Optional<ReconSellerEntity> findByName(String name);

    List<ReconSellerEntity> findAllByOrderByNameAsc();
}
