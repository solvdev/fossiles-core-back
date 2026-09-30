package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteAliasEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KioskSiteAliasRepository extends JpaRepository<KioskSiteAliasEntity, Long> {

    List<KioskSiteAliasEntity> findBySiteId(Long siteId);

    Optional<KioskSiteAliasEntity> findByAliasNormalized(String aliasNormalized);
}
