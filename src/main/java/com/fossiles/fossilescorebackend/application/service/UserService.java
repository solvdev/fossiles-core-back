package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.UserRequest;
import com.fossiles.fossilescorebackend.application.dto.response.UserResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.mapper.UserMapper;
import com.fossiles.fossilescorebackend.application.port.UserRepositoryPort;
import com.fossiles.fossilescorebackend.domain.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio de lógica de negocio para usuarios
 * Aquí es donde implementas toda tu lógica específica de negocio
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepositoryPort userRepositoryPort;
    private final UserMapper userMapper;

    /**
     * Crear un nuevo usuario con validaciones de negocio
     */
    public UserResponse createUser(UserRequest request) {
        // Lógica de negocio: Validar que el username no exista
        if (userRepositoryPort.existsByUsername(request.getUsername())) {
            throw new BusinessException("Username already exists: " + request.getUsername());
        }

        // Lógica de negocio: Validar que el email no exista
        if (userRepositoryPort.existsByEmail(request.getEmail())) {
            throw new BusinessException("Email already exists: " + request.getEmail());
        }

        // Lógica de negocio: Establecer status por defecto
        if (request.getStatus() == null || request.getStatus().isEmpty()) {
            request.setStatus("active");
        }

        // Convertir Request a Domain
        User user = userMapper.toDomain(request);
        
        // Aquí puedes agregar más lógica de negocio, por ejemplo:
        // - Encriptar password
        // - Generar códigos de activación
        // - Enviar emails de bienvenida
        // - etc.

        // Guardar
        User savedUser = userRepositoryPort.save(user);
        
        return userMapper.toResponse(savedUser);
    }

    /**
     * Obtener usuario por ID
     */
    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {
        User user = userRepositoryPort.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id));
        
        return userMapper.toResponse(user);
    }

    /**
     * Obtener usuario por username
     */
    @Transactional(readOnly = true)
    public UserResponse getUserByUsername(String username) {
        User user = userRepositoryPort.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException("User", "username", username));
        
        return userMapper.toResponse(user);
    }

    /**
     * Obtener todos los usuarios
     */
    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        return userRepositoryPort.findAll().stream()
            .map(userMapper::toResponse)
            .collect(Collectors.toList());
    }

    /**
     * Actualizar usuario con lógica de negocio
     */
    public UserResponse updateUser(Long id, UserRequest request) {
        // Lógica de negocio: Verificar que el usuario existe
        User existingUser = userRepositoryPort.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id));

        // Lógica de negocio: Validar que el nuevo username no esté en uso (si cambió)
        if (!existingUser.getUsername().equals(request.getUsername()) 
            && userRepositoryPort.existsByUsername(request.getUsername())) {
            throw new BusinessException("Username already exists: " + request.getUsername());
        }

        // Lógica de negocio: Validar que el nuevo email no esté en uso (si cambió)
        if (!existingUser.getEmail().equals(request.getEmail()) 
            && userRepositoryPort.existsByEmail(request.getEmail())) {
            throw new BusinessException("Email already exists: " + request.getEmail());
        }

        // Actualizar campos
        existingUser.setUsername(request.getUsername());
        existingUser.setEmail(request.getEmail());
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            existingUser.setPassword(request.getPassword());
            // Aquí podrías encriptar el password
        }
        if (request.getStatus() != null) {
            existingUser.setStatus(request.getStatus());
        }

        User updatedUser = userRepositoryPort.save(existingUser);
        return userMapper.toResponse(updatedUser);
    }

    /**
     * Eliminar usuario con validaciones de negocio
     */
    public void deleteUser(Long id) {
        // Lógica de negocio: Verificar que existe
        if (!userRepositoryPort.findById(id).isPresent()) {
            throw new ResourceNotFoundException("User", id);
        }

        // Lógica de negocio: Aquí podrías agregar validaciones adicionales
        // Por ejemplo: No permitir eliminar usuarios con órdenes activas
        // if (userHasActiveOrders(id)) {
        //     throw new BusinessException("Cannot delete user with active orders");
        // }

        userRepositoryPort.deleteById(id);
    }

    /**
     * Activar/Desactivar usuario
     */
    public UserResponse changeUserStatus(Long id, String status) {
        User user = userRepositoryPort.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id));

        // Lógica de negocio: Validar status válido
        if (!status.equals("active") && !status.equals("inactive")) {
            throw new BusinessException("Invalid status. Must be 'active' or 'inactive'");
        }

        user.setStatus(status);
        User updatedUser = userRepositoryPort.save(user);
        return userMapper.toResponse(updatedUser);
    }
}

