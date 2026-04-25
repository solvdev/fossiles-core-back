package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LocationRepository extends JpaRepository<LocationEntity, Long> {
    Optional<LocationEntity> findByCode(String code);
    java.util.List<LocationEntity> findByEncargadoIdOrderByNameAsc(Long encargadoId);
    
    @EntityGraph(attributePaths = {"encargado"})
    Optional<LocationEntity> findById(Long id);
    
    @EntityGraph(attributePaths = {"encargado"})
    java.util.List<LocationEntity> findAll();
}

