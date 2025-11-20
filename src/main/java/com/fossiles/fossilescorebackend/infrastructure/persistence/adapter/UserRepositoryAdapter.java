package com.fossiles.fossilescorebackend.infrastructure.persistence.adapter;

import com.fossiles.fossilescorebackend.application.mapper.UserMapper;
import com.fossiles.fossilescorebackend.application.port.UserRepositoryPort;
import com.fossiles.fossilescorebackend.domain.model.User;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Adaptador (Adapter) que implementa el puerto UserRepositoryPort
 * Conecta la capa de aplicación con la infraestructura (JPA)
 */
@Component
@RequiredArgsConstructor
public class UserRepositoryAdapter implements UserRepositoryPort {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Override
    public User save(User user) {
        UserEntity entity = userMapper.toEntity(user);
        UserEntity saved = userRepository.save(entity);
        return userMapper.toDomain(saved);
    }

    @Override
    public Optional<User> findById(Long id) {
        return userRepository.findById(id)
            .map(userMapper::toDomain);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username)
            .map(userMapper::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email)
            .map(userMapper::toDomain);
    }

    @Override
    public List<User> findAll() {
        return userRepository.findAll().stream()
            .map(userMapper::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public void deleteById(Long id) {
        userRepository.deleteById(id);
    }

    @Override
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    @Override
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }
}

