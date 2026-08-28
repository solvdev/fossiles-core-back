package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SystemAnnouncementEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SystemAnnouncementRepository extends JpaRepository<SystemAnnouncementEntity, Long> {

    @EntityGraph(attributePaths = {"createdByUser", "dismissedByUser"})
    Optional<SystemAnnouncementEntity> findFirstByIsActiveTrueAndExpiresAtAfterOrderByCreatedAtDesc(LocalDateTime now);

    @EntityGraph(attributePaths = {"createdByUser", "dismissedByUser"})
    List<SystemAnnouncementEntity> findTop10ByOrderByCreatedAtDesc();

    @Modifying
    @Query("UPDATE SystemAnnouncementEntity a SET a.isActive = false, a.dismissedAt = :dismissedAt, a.dismissedByUser.id = :userId WHERE a.isActive = true")
    int deactivateAllActive(@Param("dismissedAt") LocalDateTime dismissedAt, @Param("userId") Long userId);
}
