package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EnvioDetalleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnvioDetalleRepository extends JpaRepository<EnvioDetalleEntity, Long> {
    
    List<EnvioDetalleEntity> findByEnvioId(Long envioId);
    
    Optional<EnvioDetalleEntity> findByEnvioIdAndProductId(Long envioId, Long productId);
    
    void deleteByEnvioId(Long envioId);
}

