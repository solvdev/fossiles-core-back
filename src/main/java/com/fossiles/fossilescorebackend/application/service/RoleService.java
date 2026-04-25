package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.RoleRequest;
import com.fossiles.fossilescorebackend.application.dto.response.RoleResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.mapper.RoleMapper;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PermissionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PermissionRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RoleMapper roleMapper;

    public RoleResponse create(RoleRequest request) throws BusinessException, ResourceNotFoundException {
        if (roleRepository.existsByName(request.getName())) {
            throw new BusinessException("Role name already exists: " + request.getName());
        }

        RoleEntity role = roleMapper.toEntity(request);
        role.setPermissions(resolvePermissions(request.getPermissionIds()));
        RoleEntity saved = roleRepository.save(role);
        return roleMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> findAll() {
        return roleRepository.findAll().stream()
                .map(roleMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public RoleResponse findById(Long id) throws ResourceNotFoundException {
        RoleEntity role = roleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Role", id));
        return roleMapper.toResponse(role);
    }

    public RoleResponse update(Long id, RoleRequest request) throws ResourceNotFoundException, BusinessException {
        RoleEntity role = roleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Role", id));

        if (!role.getName().equals(request.getName()) && roleRepository.existsByName(request.getName())) {
            throw new BusinessException("Role name already exists: " + request.getName());
        }

        role.setName(request.getName());
        role.setDescription(request.getDescription());
        role.setPermissions(resolvePermissions(request.getPermissionIds()));

        RoleEntity updated = roleRepository.save(role);
        return roleMapper.toResponse(updated);
    }

    public void delete(Long id) throws ResourceNotFoundException {
        if (!roleRepository.existsById(id)) {
            throw new ResourceNotFoundException("Role", id);
        }
        roleRepository.deleteById(id);
    }

    private Set<PermissionEntity> resolvePermissions(Set<Long> permissionIds) throws ResourceNotFoundException {
        if (permissionIds == null || permissionIds.isEmpty()) {
            return new HashSet<>();
        }
        List<PermissionEntity> permissions = permissionRepository.findAllById(permissionIds);
        if (permissions.size() != permissionIds.size()) {
            throw new ResourceNotFoundException("Permission", "ids", permissionIds.toString());
        }
        return new HashSet<>(permissions);
    }
}

