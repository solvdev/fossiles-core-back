package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserActivityLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserActivityLogRepository extends JpaRepository<UserActivityLogEntity, Long> {

    List<UserActivityLogEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    default List<UserActivityLogEntity> findTop10ByUserIdOrderByCreatedAtDesc(Long userId) {
        return findByUserIdOrderByCreatedAtDesc(userId, Pageable.ofSize(10));
    }

    @Query("SELECT l FROM UserActivityLogEntity l WHERE l.user.id = :userId ORDER BY l.createdAt DESC LIMIT 1")
    Optional<UserActivityLogEntity> findLatestByUserId(@Param("userId") Long userId);
}
