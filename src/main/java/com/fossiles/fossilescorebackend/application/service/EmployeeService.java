package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.EmployeeRequest;
import com.fossiles.fossilescorebackend.application.dto.response.EmployeeResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.mapper.EmployeeMapper;
import com.fossiles.fossilescorebackend.domain.model.Employee;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.DepartmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CostCenterRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OperationalUnitRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeMapper employeeMapper;
    private final DepartmentRepository departmentRepository;
    private final CostCenterRepository costCenterRepository;
    private final OperationalUnitRepository operationalUnitRepository;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    /**
     * Crear un nuevo empleado con validaciones de negocio
     */
    public EmployeeResponse createEmployee(EmployeeRequest request) throws BusinessException, ResourceNotFoundException {
        // Validar que el email no exista
        if (request.getEmail() != null && employeeRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Email already exists: " + request.getEmail());
        }

        // Validar que el DPI no exista
        if (request.getDpi() != null && employeeRepository.existsByDpi(request.getDpi())) {
            throw new BusinessException("DPI already exists: " + request.getDpi());
        }

        // Validar que el departamento existe
        if (request.getDepartmentId() != null && !departmentRepository.existsById(request.getDepartmentId())) {
            throw new ResourceNotFoundException("Department", request.getDepartmentId());
        }

        // Validar que el centro de costo existe (si se proporciona)
        if (request.getCostCenterId() != null && !costCenterRepository.existsById(request.getCostCenterId())) {
            throw new ResourceNotFoundException("Cost Center", request.getCostCenterId());
        }

        // Validar que la unidad operativa existe (si se proporciona)
        if (request.getOperationalUnitId() != null && !operationalUnitRepository.existsById(request.getOperationalUnitId())) {
            throw new ResourceNotFoundException("Operational Unit", request.getOperationalUnitId());
        }

        // Establecer status por defecto
        if (request.getStatus() == null || request.getStatus().isEmpty()) {
            request.setStatus("active");
        }

        // Convertir Request a Domain
        Employee employee = employeeMapper.toDomain(request);

        // Obtener el usuario actual para createdBy
        Long currentUserId = getCurrentUserId();
        employee.setCreatedBy(currentUserId);
        employee.setUpdatedBy(currentUserId);

        // Convertir Domain a Entity y guardar
        EmployeeEntity entity = employeeMapper.toEntity(employee);
        
        // Asociar usuario si se proporciona userId
        if (request.getUserId() != null) {
            UserEntity userEntity = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.getUserId()));
            entity.getUsers().clear();
            entity.getUsers().add(userEntity);
        }
        
        EmployeeEntity savedEntity = employeeRepository.save(entity);

        // Convertir Entity a Domain y luego a Response
        Employee savedEmployee = employeeMapper.toDomain(savedEntity);
        return employeeMapper.toResponse(savedEmployee);
    }

    /**
     * Obtener empleado por ID
     */
    @Transactional(readOnly = true)
    public EmployeeResponse getEmployeeById(Long id) throws ResourceNotFoundException {
        EmployeeEntity entity = employeeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Employee", id));
        
        Employee employee = employeeMapper.toDomain(entity);
        return employeeMapper.toResponse(employee);
    }

    /**
     * Obtener todos los empleados
     */
    @Transactional(readOnly = true)
    public List<EmployeeResponse> getAllEmployees() {
        return employeeRepository.findAll().stream()
            .map(employeeMapper::toResponse)
            .collect(Collectors.toList());
    }

    /**
     * Actualizar empleado con lógica de negocio
     */
    public EmployeeResponse updateEmployee(Long id, EmployeeRequest request) throws BusinessException, ResourceNotFoundException {
        // Verificar que el empleado existe
        EmployeeEntity existingEntity = employeeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Employee", id));

        Employee existingEmployee = employeeMapper.toDomain(existingEntity);

        // Validar que el nuevo email no esté en uso (si cambió)
        if (request.getEmail() != null 
            && !request.getEmail().equals(existingEmployee.getEmail()) 
            && employeeRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Email already exists: " + request.getEmail());
        }

        // Validar que el nuevo DPI no esté en uso (si cambió)
        if (request.getDpi() != null 
            && !request.getDpi().equals(existingEmployee.getDpi()) 
            && employeeRepository.existsByDpi(request.getDpi())) {
            throw new BusinessException("DPI already exists: " + request.getDpi());
        }

        // Validar que el departamento existe
        if (request.getDepartmentId() != null && !departmentRepository.existsById(request.getDepartmentId())) {
            throw new ResourceNotFoundException("Department", request.getDepartmentId());
        }

        // Validar que el centro de costo existe (si se proporciona)
        if (request.getCostCenterId() != null && !costCenterRepository.existsById(request.getCostCenterId())) {
            throw new ResourceNotFoundException("Cost Center", request.getCostCenterId());
        }

        // Validar que la unidad operativa existe (si se proporciona)
        if (request.getOperationalUnitId() != null && !operationalUnitRepository.existsById(request.getOperationalUnitId())) {
            throw new ResourceNotFoundException("Operational Unit", request.getOperationalUnitId());
        }

        // Validar que el usuario existe (si se proporciona)
        if (request.getUserId() != null && !userRepository.existsById(request.getUserId())) {
            throw new ResourceNotFoundException("User", request.getUserId());
        }

        // Actualizar campos
        if (request.getFirstName() != null) {
            existingEntity.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null) {
            existingEntity.setLastName(request.getLastName());
        }
        if (request.getEmail() != null) {
            existingEntity.setEmail(request.getEmail());
        }
        if (request.getPhone() != null) {
            existingEntity.setPhone(request.getPhone());
        }
        if (request.getDpi() != null) {
            existingEntity.setDpi(request.getDpi());
        }
        if (request.getHireDate() != null) {
            existingEntity.setHireDate(request.getHireDate());
        }
        if (request.getPosition() != null) {
            existingEntity.setPosition(request.getPosition());
        }
        if (request.getSalary() != null) {
            existingEntity.setSalary(request.getSalary());
        }
        if (request.getBankAccount() != null) {
            existingEntity.setBankAccount(request.getBankAccount());
        }
        if (request.getPaymentMethod() != null) {
            existingEntity.setPaymentMethod(request.getPaymentMethod());
        }
        if (request.getIgssDeduction() != null) {
            existingEntity.setIgssDeduction(request.getIgssDeduction());
        }
        if (request.getQuincenaBruta() != null) {
            existingEntity.setQuincenaBruta(request.getQuincenaBruta());
        }
        if (request.getQuincenaNeta() != null) {
            existingEntity.setQuincenaNeta(request.getQuincenaNeta());
        }
        if (request.getDepartmentId() != null) {
            existingEntity.setDepartmentId(request.getDepartmentId());
        }
        if (request.getCostCenterId() != null) {
            existingEntity.setCostCenterId(request.getCostCenterId());
        }
        if (request.getOperationalUnitId() != null) {
            existingEntity.setOperationalUnitId(request.getOperationalUnitId());
        }
        if (request.getStatus() != null) {
            existingEntity.setStatus(request.getStatus());
        }

        // Asociar o desasociar usuario si se proporciona userId
        // Si userId es null, mantener la asociación actual (no cambiar)
        // Si userId es un número válido, asociar ese usuario
        // Para desasociar explícitamente, se puede enviar userId = 0 o null (depende de la lógica del frontend)
        if (request.getUserId() != null) {
            if (request.getUserId() == 0) {
                // Desasociar usuario
                existingEntity.getUsers().clear();
            } else {
                // Asociar nuevo usuario
                UserEntity userEntity = userRepository.findById(request.getUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("User", request.getUserId()));
                existingEntity.getUsers().clear();
                existingEntity.getUsers().add(userEntity);
            }
        }

        // Obtener el usuario actual para updatedBy
        Long currentUserId = getCurrentUserId();
        existingEntity.setUpdatedBy(currentUserId);

        EmployeeEntity updatedEntity = employeeRepository.save(existingEntity);
        Employee updatedEmployee = employeeMapper.toDomain(updatedEntity);
        return employeeMapper.toResponse(updatedEmployee);
    }

    /**
     * Cambiar estado del empleado
     */
    public EmployeeResponse changeEmployeeStatus(Long id, String status) throws BusinessException, ResourceNotFoundException {
        EmployeeEntity entity = employeeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Employee", id));

        if (status == null || (!status.equals("active") && !status.equals("inactive"))) {
            throw new BusinessException("Invalid status. Must be 'active' or 'inactive'");
        }

        entity.setStatus(status);
        
        // Obtener el usuario actual para updatedBy
        Long currentUserId = getCurrentUserId();
        entity.setUpdatedBy(currentUserId);

        EmployeeEntity updatedEntity = employeeRepository.save(entity);
        Employee updatedEmployee = employeeMapper.toDomain(updatedEntity);
        return employeeMapper.toResponse(updatedEmployee);
    }

    /**
     * Eliminar empleado
     */
    public void deleteEmployee(Long id) throws ResourceNotFoundException {
        EmployeeEntity entity = employeeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Employee", id));
        
        employeeRepository.delete(entity);
    }

    /**
     * Obtener el ID del usuario actual desde el contexto de seguridad
     */
    private Long getCurrentUserId() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()) {
                String username = authentication.getName();
                // Aquí podrías obtener el ID del usuario desde el token o desde la base de datos
                // Por ahora retornamos null si no se puede obtener
                return null; // TODO: Implementar obtención del ID del usuario actual
            }
        } catch (Exception e) {
            // Si hay algún error, retornar null
        }
        return null;
    }
}

