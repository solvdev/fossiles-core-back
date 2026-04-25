package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<RoleEntity, Long> {
    @EntityGraph(attributePaths = {"permissions"})
    Optional<RoleEntity> findById(Long id);
    
    Optional<RoleEntity> findByName(String name);
    boolean existsByName(String name);
}

