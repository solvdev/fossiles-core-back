package com.fossiles.fossilescorebackend.application.port;

import com.fossiles.fossilescorebackend.domain.model.User;

import java.util.List;
import java.util.Optional;

/**
 * Puerto (Port) que define el contrato para el repositorio de usuarios
 * Esta es la interfaz que la capa de aplicación usa, sin conocer la implementación
 */
public interface UserRepositoryPort {
    User save(User user);
    Optional<User> findById(Long id);
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    List<User> findAll();
    void deleteById(Long id);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
}

