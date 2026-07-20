package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountPresenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface KioscoPhysicalCountPresenceRepository extends JpaRepository<KioscoPhysicalCountPresenceEntity, Long> {

    Optional<KioscoPhysicalCountPresenceEntity> findByCountIdAndUserId(Long countId, Long userId);

    List<KioscoPhysicalCountPresenceEntity> findByCountIdAndLastSeenAtAfterOrderByLastSeenAtDesc(
            Long countId, LocalDateTime since);

    @Modifying
    @Query("DELETE FROM KioscoPhysicalCountPresenceEntity p WHERE p.countId = :countId AND p.lastSeenAt < :before")
    int deleteStaleForCount(@Param("countId") Long countId, @Param("before") LocalDateTime before);
}
