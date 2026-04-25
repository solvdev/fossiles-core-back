package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.UserRequest;
import com.fossiles.fossilescorebackend.application.dto.request.UserStatusRequest;
import com.fossiles.fossilescorebackend.application.dto.request.UserUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.UserResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.mapper.RoleMapper;
import com.fossiles.fossilescorebackend.application.mapper.UserMapper;
import com.fossiles.fossilescorebackend.application.port.UserRepositoryPort;
import com.fossiles.fossilescorebackend.domain.model.CostCenter;
import com.fossiles.fossilescorebackend.domain.model.Department;
import com.fossiles.fossilescorebackend.domain.model.OperationalUnit;
import com.fossiles.fossilescorebackend.domain.model.Role;
import com.fossiles.fossilescorebackend.domain.model.User;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DepartmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CostCenterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OperationalUnitEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.RoleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.DepartmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CostCenterRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OperationalUnitRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private final RoleMapper roleMapper;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final CostCenterRepository costCenterRepository;
    private final OperationalUnitRepository operationalUnitRepository;
    private final PasswordEncoder passwordEncoder;
    private final EncryptionUtil encryptionUtil;

    /**
     * Crear un nuevo usuario con validaciones de negocio
     */
    public UserResponse createUser(UserRequest request) throws BusinessException {
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

        DepartmentEntity department = resolveDepartment(request.getDepartmentId());
        CostCenterEntity costCenter = resolveCostCenter(request.getCostCenterId());
        OperationalUnitEntity operationalUnit = resolveOperationalUnit(request.getOperationalUnitId());
        user.setDepartment(toDepartmentDomain(department));
        user.setCostCenter(toCostCenterDomain(costCenter));
        user.setOperationalUnit(toOperationalUnitDomain(operationalUnit));
        user.setProfileImageUrl(request.getProfileImageUrl());
        
        // Desencriptar y hashear la contraseña antes de guardar
        // La contraseña viene encriptada del frontend, igual que en el login
        if (user.getPassword() != null && !user.getPassword().isEmpty()) {
            try {
                // Desencriptar la contraseña encriptada del frontend
                String decryptedPassword = encryptionUtil.decrypt(user.getPassword());
                // Hashear la contraseña en texto plano
                user.setPassword(passwordEncoder.encode(decryptedPassword));
            } catch (Exception e) {
                // Si falla la desencriptación, asumir que ya viene en texto plano (para compatibilidad)
                // y hashearla directamente
                user.setPassword(passwordEncoder.encode(user.getPassword()));
            }
        }

        // Asignar roles
        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            user.setRoles(resolveRoles(request.getRoleIds()));
        }

        // Guardar
        User savedUser = userRepositoryPort.save(user);
        
        return userMapper.toResponse(savedUser);
    }

    /**
     * Obtener usuario por ID
     */
    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) throws ResourceNotFoundException {
        User user = userRepositoryPort.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id));
        
        return userMapper.toResponse(user);
    }

    /**
     * Obtener usuario por username
     */
    @Transactional(readOnly = true)
    public UserResponse getUserByUsername(String username) throws ResourceNotFoundException {
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
    public UserResponse updateUser(Long id, UserUpdateRequest request) throws BusinessException, ResourceNotFoundException {
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
        existingUser.setFirstName(request.getFirstName());
        existingUser.setLastName(request.getLastName());
        if (request.getProfileImageUrl() != null) {
            existingUser.setProfileImageUrl(request.getProfileImageUrl());
        }
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            // Desencriptar y hashear la nueva contraseña
            // La contraseña viene encriptada del frontend, igual que en el login
            try {
                // Desencriptar la contraseña encriptada del frontend
                String decryptedPassword = encryptionUtil.decrypt(request.getPassword());
                // Hashear la contraseña en texto plano
                existingUser.setPassword(passwordEncoder.encode(decryptedPassword));
            } catch (Exception e) {
                // Si falla la desencriptación, asumir que ya viene en texto plano (para compatibilidad)
                // y hashearla directamente
                existingUser.setPassword(passwordEncoder.encode(request.getPassword()));
            }
        }
        if (request.getStatus() != null) {
            existingUser.setStatus(request.getStatus());
        }

        if (request.getRoleIds() != null) {
            existingUser.setRoles(resolveRoles(request.getRoleIds()));
        }

        DepartmentEntity department = resolveDepartment(request.getDepartmentId());
        CostCenterEntity costCenter = resolveCostCenter(request.getCostCenterId());
        OperationalUnitEntity operationalUnit = resolveOperationalUnit(request.getOperationalUnitId());
        existingUser.setDepartment(toDepartmentDomain(department));
        existingUser.setCostCenter(toCostCenterDomain(costCenter));
        existingUser.setOperationalUnit(toOperationalUnitDomain(operationalUnit));

        User updatedUser = userRepositoryPort.save(existingUser);
        return userMapper.toResponse(updatedUser);
    }

    /**
     * Eliminar usuario con validaciones de negocio
     */
    public void deleteUser(Long id) throws ResourceNotFoundException {
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
    public UserResponse changeUserStatus(Long id, UserStatusRequest request) throws BusinessException, ResourceNotFoundException {
        User user = userRepositoryPort.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id));

        // Lógica de negocio: Validar status válido
        String status = request.getStatus().toLowerCase();
        if (!status.equals("active") && !status.equals("inactive")) {
            throw new BusinessException("Invalid status. Must be 'active' or 'inactive'");
        }

        user.setStatus(status);
        User updatedUser = userRepositoryPort.save(user);
        return userMapper.toResponse(updatedUser);
    }

    @Transactional(readOnly = true)
    public boolean canManageUsers(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }

        return userRepositoryPort.findByUsername(username)
                .map(User::getRoles)
                .map(roles -> roles != null && roles.stream()
                        .anyMatch(role -> {
                            String normalized = normalizeRoleName(role != null ? role.getName() : null);
                            return normalized.contains("ADMIN")
                                    || normalized.contains("RRHH")
                                    || normalized.contains("RECURSOSHUMANOS")
                                    || normalized.contains("HUMANRESOURCES")
                                    || normalized.equals("HR");
                        }))
                .orElse(false);
    }

    public UserResponse updateUserProfileImage(Long userId, String imageUrl) throws ResourceNotFoundException {
        User user = userRepositoryPort.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        user.setProfileImageUrl(imageUrl);
        User updatedUser = userRepositoryPort.save(user);
        return userMapper.toResponse(updatedUser);
    }

    private String normalizeRoleName(String roleName) {
        if (roleName == null) {
            return "";
        }
        return roleName
                .toUpperCase(Locale.ROOT)
                .replace("Á", "A")
                .replace("É", "E")
                .replace("Í", "I")
                .replace("Ó", "O")
                .replace("Ú", "U")
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
    }

    private Set<Role> resolveRoles(Set<Long> roleIds) throws BusinessException {
        if (roleIds == null || roleIds.isEmpty()) {
            return new HashSet<>();
        }
        List<RoleEntity> entities = roleRepository.findAllById(roleIds);
        if (entities.size() != roleIds.size()) {
            throw new BusinessException("One or more roles do not exist");
        }
        return entities.stream()
                .map(roleMapper::toDomain)
                .collect(Collectors.toSet());
    }

    private DepartmentEntity resolveDepartment(Long departmentId) throws BusinessException {
        return departmentRepository.findById(departmentId)
                .orElseThrow(() -> new BusinessException("Department does not exist: " + departmentId));
    }

    private CostCenterEntity resolveCostCenter(Long costCenterId) throws BusinessException {
        return costCenterRepository.findById(costCenterId)
                .orElseThrow(() -> new BusinessException("Cost center does not exist: " + costCenterId));
    }

    private OperationalUnitEntity resolveOperationalUnit(Long operationalUnitId) throws BusinessException {
        return operationalUnitRepository.findById(operationalUnitId)
                .orElseThrow(() -> new BusinessException("Operational unit does not exist: " + operationalUnitId));
    }

    private Department toDepartmentDomain(DepartmentEntity entity) {
        if (entity == null) return null;
        return Department.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .description(entity.getDescription())
                .build();
    }

    private CostCenter toCostCenterDomain(CostCenterEntity entity) {
        if (entity == null) return null;
        return CostCenter.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .description(entity.getDescription())
                .build();
    }

    private OperationalUnit toOperationalUnitDomain(OperationalUnitEntity entity) {
        if (entity == null) return null;
        return OperationalUnit.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .description(entity.getDescription())
                .build();
    }
}

