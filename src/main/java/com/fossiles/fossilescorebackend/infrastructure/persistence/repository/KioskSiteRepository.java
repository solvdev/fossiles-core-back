package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KioskSiteRepository extends JpaRepository<KioskSiteEntity, Long> {

    List<KioskSiteEntity> findAllByOrderBySortOrderAscNameAsc();

    /** Sitios que sí entran a los reportes (excluye los marcados como externos). */
    List<KioskSiteEntity> findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc();

    Optional<KioskSiteEntity> findByNameIgnoreCase(String name);

    Optional<KioskSiteEntity> findByLocationId(Long locationId);

    @Query("SELECT COALESCE(MAX(s.sortOrder), 0) FROM KioskSiteEntity s")
    Integer findMaxSortOrder();
}
