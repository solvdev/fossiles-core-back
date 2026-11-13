package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionSpecialDeskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionSpecialDeskRepository extends JpaRepository<ProductionSpecialDeskEntity, Long> {

    List<ProductionSpecialDeskEntity> findByActiveTrueOrderByDeskAsc();

    List<ProductionSpecialDeskEntity> findAllByOrderByDeskAsc();

    Optional<ProductionSpecialDeskEntity> findByDesk(Integer desk);
}
