package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OtherRentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OtherRentRepository extends JpaRepository<OtherRentEntity, Long> {

    List<OtherRentEntity> findByActiveTrueOrderByNameAscIdAsc();

    Optional<OtherRentEntity> findByIdAndActiveTrue(Long id);

    /** Nombre comparado como el índice: sin espacios a los lados y sin distinguir mayúsculas. */
    @Query("SELECT r FROM OtherRentEntity r WHERE r.active = true AND r.category = :category "
            + "AND lower(trim(r.name)) = lower(trim(:name))")
    List<OtherRentEntity> findActiveByCategoryAndName(@Param("category") String category, @Param("name") String name);
}
