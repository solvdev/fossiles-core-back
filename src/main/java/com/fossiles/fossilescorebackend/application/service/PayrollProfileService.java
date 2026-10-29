package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.PayrollProfileRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollProfileResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DepartmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeSalaryHistoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.DepartmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeSalaryHistoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.PayrollRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ficha laboral de todos los colaboradores en una tabla: vinculación, grupo, forma de pago, cuenta, salario,
 * bonificación y fecha de baja.
 */
@Service
@RequiredArgsConstructor
public class PayrollProfileService {

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final EmployeeSalaryHistoryRepository salaryHistoryRepository;
    private final PayrollAccessGuard accessGuard;

    @Transactional(readOnly = true)
    public List<PayrollProfileResponse> list() {
        accessGuard.assertCanViewRuns();
        Map<Long, DepartmentEntity> departments = departmentRepository.findAll().stream()
                .collect(Collectors.toMap(DepartmentEntity::getId, Function.identity()));
        return employeeRepository.findAll().stream()
                .sorted(Comparator.comparing(EmployeeChargeService::fullName))
                .map(employee -> toResponse(employee, departments.get(employee.getDepartmentId())))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public PayrollProfileResponse update(Long employeeId, PayrollProfileRequest request)
            throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanCreateRuns();
        EmployeeEntity employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", employeeId));
        String type = PayrollRules.normalize(request.getEmploymentType());
        if (type == null || !PayrollRules.EMPLOYMENT_TYPES.contains(type)) {
            throw new BusinessException("Tipo de vinculación inválido. Use PLANILLA o SERVICIOS.");
        }
        String group = PayrollRules.normalize(request.getPayGroup());
        if (group == null || !PayrollRules.PAY_GROUPS.contains(group)) {
            throw new BusinessException("Grupo de pago inválido. Use PLANTA o ADMINISTRACION.");
        }
        String method = PayrollRules.canonicalPaymentMethod(request.getPaymentMethod());
        if (method == null) {
            throw new BusinessException("Forma de pago inválida. Use TRANSFERENCIA BANCARIA, CHEQUE o EFECTIVO.");
        }
        if (request.getTerminationDate() != null && employee.getHireDate() != null
                && request.getTerminationDate().isBefore(employee.getHireDate())) {
            throw new BusinessException("La fecha de baja no puede ser anterior a la de contratación.");
        }
        boolean salaryChanged = !sameAmount(employee.getSalary(), request.getSalary())
                || !sameAmount(employee.getBonification(), request.getBonification());
        employee.setEmploymentType(type);
        employee.setPayGroup(group);
        employee.setPaymentMethod(method);
        employee.setBankAccount(blankToNull(request.getBankAccount()));
        employee.setSalary(request.getSalary());
        employee.setBonification(request.getBonification());
        employee.setTerminationDate(request.getTerminationDate());
        employee.setNit(blankToNull(request.getNit()));
        employee.setIgssAffiliation(blankToNull(request.getIgssAffiliation()));
        employee.setUpdatedBy(accessGuard.currentUserId());
        EmployeeEntity saved = employeeRepository.save(employee);
        if (salaryChanged) {
            salaryHistoryRepository.save(EmployeeSalaryHistoryEntity.builder()
                    .employeeId(saved.getId())
                    .effectiveFrom(GuatemalaDateTime.today())
                    .salary(saved.getSalary())
                    .bonification(saved.getBonification())
                    .recordedBy(accessGuard.currentUserId())
                    .build());
        }
        DepartmentEntity department = saved.getDepartmentId() == null ? null
                : departmentRepository.findById(saved.getDepartmentId()).orElse(null);
        return toResponse(saved, department);
    }

    private PayrollProfileResponse toResponse(EmployeeEntity employee, DepartmentEntity department) {
        String departmentGroup = department == null ? null
                : PayrollRules.payGroupForDepartment(department.getName(), department.getCode());
        return PayrollProfileResponse.builder()
                .id(employee.getId())
                .fullName(EmployeeChargeService.fullName(employee))
                .position(employee.getPosition())
                .departmentId(employee.getDepartmentId())
                .departmentName(department == null ? null : department.getName())
                .status(employee.getStatus())
                .hireDate(employee.getHireDate())
                .terminationDate(employee.getTerminationDate())
                .employmentType(employee.getEmploymentType())
                .payGroup(employee.getPayGroup())
                .departmentPayGroup(departmentGroup)
                .paymentMethod(employee.getPaymentMethod())
                .bankAccount(employee.getBankAccount())
                .salary(employee.getSalary())
                .bonification(employee.getBonification())
                .nit(employee.getNit())
                .igssAffiliation(employee.getIgssAffiliation())
                .warnings(warnings(employee, department, departmentGroup))
                .build();
    }

    static List<String> warnings(EmployeeEntity employee, DepartmentEntity department, String departmentGroup) {
        List<String> warnings = new ArrayList<>();
        if (employee.getSalary() == null || employee.getSalary().signum() <= 0) {
            warnings.add("Sin salario");
        }
        if (department == null) {
            warnings.add("Sin departamento");
        } else if (!Objects.equals(departmentGroup, employee.getPayGroup())) {
            warnings.add("Grupo distinto al de su departamento");
        }
        if (employee.getPaymentMethod() == null || employee.getPaymentMethod().isBlank()) {
            warnings.add("Sin forma de pago");
        } else if (PayrollRules.PAYMENT_TRANSFER.equals(employee.getPaymentMethod())
                && (employee.getBankAccount() == null || employee.getBankAccount().isBlank())) {
            warnings.add("Transferencia sin número de cuenta");
        }
        if (PayrollRules.EMPLOYMENT_PLANILLA.equals(employee.getEmploymentType())
                && employee.getBonification() != null && employee.getBonification().signum() == 0) {
            warnings.add("Bonificación 0");
        }
        return warnings;
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return Objects.equals(a, b) || (a != null && b != null && a.compareTo(b) == 0);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
