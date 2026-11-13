package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeRepository extends JpaRepository<EmployeeEntity, Long> {
    Optional<EmployeeEntity> findByEmail(String email);
    Optional<EmployeeEntity> findByDpi(String dpi);
    boolean existsByEmail(String email);
    boolean existsByDpi(String dpi);

    /**
     * Quién es el usuario de cada empleado, en una sola consulta y sin cargar la entidad del usuario (que arrastra sus
     * roles y su departamento): id del empleado, id, usuario, correo, nombre, apellido y estado.
     */
    @Query("SELECT e.id, u.id, u.username, u.email, u.firstName, u.lastName, u.status FROM EmployeeEntity e JOIN e.users u ORDER BY e.id, u.id")
    List<Object[]> findUserSummaries();
}

