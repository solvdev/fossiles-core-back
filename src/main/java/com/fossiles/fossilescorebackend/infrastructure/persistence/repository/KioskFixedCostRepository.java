package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskFixedCostEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KioskFixedCostRepository
        extends JpaRepository<KioskFixedCostEntity, KioskFixedCostEntity.Key> {

    List<KioskFixedCostEntity> findByPeriodYear(Integer periodYear);

    List<KioskFixedCostEntity> findByPeriodYearAndPeriodMonth(Integer periodYear, Integer periodMonth);

    /**
     * Costos fijos de una categoría por kiosco real (con location_id), el mes más reciente primero. Cada fila es
     * {locationId, amount, year, month}; se usa para tomar la renta vigente de cada kiosco.
     */
    @Query("SELECT s.locationId, f.amount, f.periodYear, f.periodMonth FROM KioskFixedCostEntity f, KioskSiteEntity s "
            + "WHERE f.siteId = s.id AND s.locationId IS NOT NULL AND f.categoryCode = :code AND f.amount > 0 "
            + "ORDER BY s.locationId ASC, f.periodYear DESC, f.periodMonth DESC")
    List<Object[]> findByCategoryWithLocation(@Param("code") String code);
}
