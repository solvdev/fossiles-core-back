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
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DepartmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeSalaryHistoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeSalaryHistoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.JwtUtil;
import com.fossiles.fossilescorebackend.infrastructure.util.PayrollRules;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
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
    private final EmployeeSalaryHistoryRepository salaryHistoryRepository;
    private final SecurityUtil securityUtil;
    private final PayrollAccessGuard payrollAccessGuard;
    private final com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayrollRunLineRepository payrollRunLineRepository;
    private final com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeChargeRepository employeeChargeRepository;

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

        normalizePayrollFields(request);
        request.setNit(blankToNull(request.getNit()));
        request.setIgssAffiliation(blankToNull(request.getIgssAffiliation()));
        if (request.getPayGroup() == null) {
            request.setPayGroup(payGroupForDepartment(request.getDepartmentId()));
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
        if (savedEntity.getSalary() != null || savedEntity.getBonification() != null) {
            recordSalaryHistory(savedEntity, savedEntity.getHireDate() != null ? savedEntity.getHireDate() : GuatemalaDateTime.today());
        }

        // Convertir Entity a Domain y luego a Response
        Employee savedEmployee = employeeMapper.toDomain(savedEntity);
        return withSalaryAccess(employeeMapper.toResponse(savedEmployee), payrollAccessGuard.canViewSalaries());
    }

    /**
     * Obtener empleado por ID
     */
    @Transactional(readOnly = true)
    public EmployeeResponse getEmployeeById(Long id) throws ResourceNotFoundException {
        EmployeeEntity entity = employeeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Employee", id));
        
        Employee employee = employeeMapper.toDomain(entity);
        return withSalaryAccess(employeeMapper.toResponse(employee), payrollAccessGuard.canViewSalaries());
    }

    /**
     * Obtener todos los empleados
     */
    @Transactional(readOnly = true)
    public List<EmployeeResponse> getAllEmployees() {
        boolean salaries = payrollAccessGuard.canViewSalaries();
        return employeeRepository.findAll().stream()
            .map(employeeMapper::toResponse)
            .map(response -> withSalaryAccess(response, salaries))
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

        normalizePayrollFields(request);
        boolean payrollAccess = payrollAccessGuard.canViewSalaries();
        if (!payrollAccess) {
            assertNoPayrollChanges(request, existingEntity);
        }
        BigDecimal previousSalary = existingEntity.getSalary();
        BigDecimal previousBonification = existingEntity.getBonification();
        boolean departmentChanged = request.getDepartmentId() != null
            && !request.getDepartmentId().equals(existingEntity.getDepartmentId());

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
        if (Boolean.TRUE.equals(request.getClearBonification())) {
            existingEntity.setBonification(null);
        } else if (request.getBonification() != null) {
            existingEntity.setBonification(request.getBonification());
        }
        if (request.getEmploymentType() != null) {
            existingEntity.setEmploymentType(request.getEmploymentType());
        }
        if (request.getPayGroup() != null) {
            existingEntity.setPayGroup(request.getPayGroup());
        } else if (departmentChanged && payrollAccess) {
            // Quien no lleva la planilla cambia el departamento sin mover el grupo; Fichas avisa la diferencia.
            existingEntity.setPayGroup(payGroupForDepartment(request.getDepartmentId()));
        }
        if (request.getNit() != null) {
            existingEntity.setNit(blankToNull(request.getNit()));
        }
        if (request.getIgssAffiliation() != null) {
            existingEntity.setIgssAffiliation(blankToNull(request.getIgssAffiliation()));
        }
        if (Boolean.TRUE.equals(request.getClearTerminationDate())) {
            existingEntity.setTerminationDate(null);
        } else if (request.getTerminationDate() != null) {
            existingEntity.setTerminationDate(request.getTerminationDate());
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
        if (!sameAmount(previousSalary, updatedEntity.getSalary())
            || !sameAmount(previousBonification, updatedEntity.getBonification())) {
            recordSalaryHistory(updatedEntity, GuatemalaDateTime.today());
        }
        Employee updatedEmployee = employeeMapper.toDomain(updatedEntity);
        return withSalaryAccess(employeeMapper.toResponse(updatedEmployee), payrollAccessGuard.canViewSalaries());
    }

    /** Salarios y bonificaciones solo los ven administradores y Contabilidad; para los demás van vacíos. */
    private static EmployeeResponse withSalaryAccess(EmployeeResponse response, boolean allowed) {
        if (!allowed) {
            response.setSalary(null);
            response.setBonification(null);
            response.setIgssDeduction(null);
            response.setQuincenaBruta(null);
            response.setQuincenaNeta(null);
        }
        return response;
    }

    /**
     * Para quien no lleva la planilla: rechaza cualquier monto, aunque coincida con el actual, para que no se pueda
     * deducir el salario; los datos de pago se rechazan solo si cambian.
     */
    private void assertNoPayrollChanges(EmployeeRequest request, EmployeeEntity current) throws BusinessException {
        boolean sendsAmounts = request.getSalary() != null || request.getBonification() != null
            || Boolean.TRUE.equals(request.getClearBonification())
            || request.getIgssDeduction() != null || request.getQuincenaBruta() != null || request.getQuincenaNeta() != null;
        if (sendsAmounts) {
            throw new BusinessException("Solo administración y Contabilidad pueden ver o cambiar salarios y bonificaciones.");
        }
        boolean changesPayment = (request.getBankAccount() != null && !Objects.equals(blankToNull(request.getBankAccount()), blankToNull(current.getBankAccount())))
            || (request.getPaymentMethod() != null && !request.getPaymentMethod().isBlank()
                && !Objects.equals(request.getPaymentMethod(), PayrollRules.canonicalPaymentMethod(current.getPaymentMethod())))
            || (request.getEmploymentType() != null && !request.getEmploymentType().equals(current.getEmploymentType()))
            || (request.getPayGroup() != null && !request.getPayGroup().equals(current.getPayGroup()))
            || (request.getTerminationDate() != null && !request.getTerminationDate().equals(current.getTerminationDate()))
            || (Boolean.TRUE.equals(request.getClearTerminationDate()) && current.getTerminationDate() != null);
        if (changesPayment) {
            throw new BusinessException("Solo administración y Contabilidad pueden cambiar la cuenta, la forma de pago, la vinculación,"
                + " el grupo de pago o la fecha de baja.");
        }
    }

    /** Valida los campos de la ficha laboral y los deja en su valor canónico. */
    private void normalizePayrollFields(EmployeeRequest request) throws BusinessException {
        if (request.getEmploymentType() != null) {
            String type = PayrollRules.normalize(request.getEmploymentType());
            if (!PayrollRules.EMPLOYMENT_TYPES.contains(type)) {
                throw new BusinessException("Tipo de vinculación inválido. Use PLANILLA o SERVICIOS.");
            }
            request.setEmploymentType(type);
        }
        if (request.getPayGroup() != null) {
            String group = PayrollRules.normalize(request.getPayGroup());
            if (group.isEmpty()) {
                group = null;
            } else if (!PayrollRules.PAY_GROUPS.contains(group)) {
                throw new BusinessException("Grupo de pago inválido. Use PLANTA o ADMINISTRACION.");
            }
            request.setPayGroup(group);
        }
        if (request.getPaymentMethod() != null && !request.getPaymentMethod().isBlank()) {
            String method = PayrollRules.canonicalPaymentMethod(request.getPaymentMethod());
            if (method == null) {
                throw new BusinessException("Forma de pago inválida. Use TRANSFERENCIA BANCARIA, CHEQUE o EFECTIVO.");
            }
            request.setPaymentMethod(method);
        }
    }

    private String payGroupForDepartment(Long departmentId) {
        if (departmentId == null) {
            return PayrollRules.GROUP_ADMINISTRACION;
        }
        DepartmentEntity department = departmentRepository.findById(departmentId).orElse(null);
        return department == null
            ? PayrollRules.GROUP_ADMINISTRACION
            : PayrollRules.payGroupForDepartment(department.getName(), department.getCode());
    }

    private void recordSalaryHistory(EmployeeEntity employee, java.time.LocalDate effectiveFrom) {
        salaryHistoryRepository.save(EmployeeSalaryHistoryEntity.builder()
            .employeeId(employee.getId())
            .effectiveFrom(effectiveFrom)
            .salary(employee.getSalary())
            .bonification(employee.getBonification())
            .recordedBy(securityUtil.getCurrentUserId())
            .build());
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return Objects.equals(a, b) || (a != null && b != null && a.compareTo(b) == 0);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
        return withSalaryAccess(employeeMapper.toResponse(updatedEmployee), payrollAccessGuard.canViewSalaries());
    }

    /**
     * Eliminar empleado
     */
    public void deleteEmployee(Long id) throws ResourceNotFoundException, BusinessException {
        EmployeeEntity entity = employeeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Employee", id));
        if (payrollRunLineRepository.existsByEmployeeId(id) || employeeChargeRepository.existsByEmployeeId(id)) {
            throw new BusinessException("El colaborador tiene planillas o cargos registrados; márquelo inactivo con su fecha de baja"
                + " en lugar de eliminarlo.");
        }
        
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

