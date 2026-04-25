package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EnvioEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnvioRepository extends JpaRepository<EnvioEntity, Long> {
    
    List<EnvioEntity> findByDistribucionId(Long distribucionId);
    
    Optional<EnvioEntity> findByDistribucionIdAndNumeroEnvio(Long distribucionId, String numeroEnvio);
    
    List<EnvioEntity> findByLocationId(Long locationId);
    
    @Query("SELECT COALESCE(MAX(CAST(SUBSTRING(e.numeroEnvio, 5) AS int)), 0) FROM EnvioEntity e WHERE e.distribucionId = :distribucionId AND e.numeroEnvio LIKE 'ENV-%'")
    Integer findMaxNumeroEnvioByDistribucion(Long distribucionId);
}

