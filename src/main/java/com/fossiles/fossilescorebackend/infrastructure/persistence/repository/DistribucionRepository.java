package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DistribucionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DistribucionRepository extends JpaRepository<DistribucionEntity, Long> {
    
    Optional<DistribucionEntity> findByNumeroDistribucion(String numeroDistribucion);
    
    List<DistribucionEntity> findByFecha(LocalDate fecha);
    
    List<DistribucionEntity> findByEstado(String estado);
    
    @Query("SELECT COALESCE(MAX(CAST(SUBSTRING(d.numeroDistribucion, 4) AS int)), 0) FROM DistribucionEntity d WHERE d.numeroDistribucion LIKE 'DIS%'")
    Integer findMaxNumeroDistribucion();
}

