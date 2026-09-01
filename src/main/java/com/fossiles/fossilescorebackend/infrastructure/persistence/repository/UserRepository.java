package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, Long> {
    @EntityGraph(attributePaths = {"roles", "roles.permissions", "department", "costCenter", "operationalUnit"})
    Optional<UserEntity> findById(Long id);
    
    @EntityGraph(attributePaths = {"roles", "roles.permissions", "department", "costCenter", "operationalUnit"})
    Optional<UserEntity> findByUsername(String username);
    
    @EntityGraph(attributePaths = {"roles", "roles.permissions", "department", "costCenter", "operationalUnit"})
    Optional<UserEntity> findByEmail(String email);

    @EntityGraph(attributePaths = {"roles", "roles.permissions", "department", "costCenter", "operationalUnit"})
    List<UserEntity> findAll();
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);

    @org.springframework.data.jpa.repository.Query("SELECT u.id FROM UserEntity u WHERE u.username = :username")
    Optional<Long> findIdByUsername(@org.springframework.data.repository.query.Param("username") String username);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE UserEntity u SET u.lastActivityAt = :now WHERE u.username = :username")
    int updateLastActivityAt(@org.springframework.data.repository.query.Param("username") String username, @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);
}

