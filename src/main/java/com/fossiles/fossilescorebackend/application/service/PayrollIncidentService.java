package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.PayrollIncidentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollIncidentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollIncidentWorkspaceResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DepartmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSupervisorAssignmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollIncidentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollRunEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.DepartmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSupervisorAssignmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayrollIncidentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayrollRunRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.PayrollRules;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Novedades de personal que cambian lo que se paga: horas extra, feriados trabajados, ausencias y vacaciones.
 * Cada sección tiene quien las registra: las supervisoras a los colaboradores de los kioscos que supervisan, el jefe de
 * producción a planta y Contabilidad a administración sin kioscos; un administrador, a todos. Lo registrado entra a la
 * planilla del período que cubre la fecha, sin aprobación aparte.
 */
@Service
@RequiredArgsConstructor
public class PayrollIncidentService {

    private static final Set<String> KINDS = Set.of(PayrollIncidentEntity.KIND_OVERTIME,
            PayrollIncidentEntity.KIND_HOLIDAY_WORKED, PayrollIncidentEntity.KIND_ABSENCE, PayrollIncidentEntity.KIND_VACATION);
    private static final int MAX_RANGE_DAYS = 60;

    private final PayrollIncidentRepository incidentRepository;
    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final LocationRepository locationRepository;
    private final KioskSupervisorAssignmentRepository assignmentRepository;
    private final PayrollRunRepository runRepository;
    private final PayrollParameterService parameterService;
    private final PayrollAccessGuard accessGuard;

    /** Qué secciones y qué kioscos puede trabajar el usuario; {@code kioskIds} nulo significa todos. */
    private record Scope(Set<String> sections, Set<Long> kioskIds) {}

    private Scope scope() {
        Set<String> sections = accessGuard.incidentSections();
        if (sections.isEmpty()) {
            throw new AccessDeniedException("No tiene permiso para registrar novedades de personal.");
        }
        Set<Long> kiosks = null;
        if (!accessGuard.isAdministrator()) {
            Long userId = accessGuard.currentUserId();
            kiosks = userId == null ? Set.of() : assignmentRepository.findBySupervisorUserId(userId).stream()
                    .map(KioskSupervisorAssignmentEntity::getKioskLocationId).collect(Collectors.toSet());
        }
        return new Scope(sections, kiosks);
    }

    public static String sectionOf(EmployeeEntity employee, DepartmentEntity department) {
        if (PayrollRules.GROUP_PLANTA.equals(employee.getPayGroup())) {
            return "PLANTA";
        }
        return department != null && PayrollRules.isKiosk(department.getName(), department.getCode()) ? "KIOSCOS" : "ADMINISTRACION";
    }

    /** A quién puede registrar novedades el usuario: personal activo de sus secciones y, en kioscos, de sus kioscos. */
    private List<EmployeeEntity> visibleEmployees(Scope scope, Map<Long, DepartmentEntity> departments) {
        LocalDate today = GuatemalaDateTime.today();
        return employeeRepository.findAll().stream()
                .filter(e -> "active".equalsIgnoreCase(e.getStatus()))
                .filter(e -> e.getTerminationDate() == null || !e.getTerminationDate().isBefore(today.minusDays(31)))
                .filter(e -> {
                    String section = sectionOf(e, departments.get(e.getDepartmentId()));
                    if (!scope.sections().contains(section)) {
                        return false;
                    }
                    return !"KIOSCOS".equals(section) || scope.kioskIds() == null
                            || (e.getKioskLocationId() != null && scope.kioskIds().contains(e.getKioskLocationId()));
                })
                .sorted((a, b) -> EmployeeChargeService.fullName(a).compareToIgnoreCase(EmployeeChargeService.fullName(b)))
                .toList();
    }

    @Transactional(readOnly = true)
    public PayrollIncidentWorkspaceResponse workspace(Integer year) {
        Scope scope = scope();
        Map<Long, DepartmentEntity> departments = departmentRepository.findAll().stream()
                .collect(Collectors.toMap(DepartmentEntity::getId, Function.identity()));
        List<EmployeeEntity> visible = visibleEmployees(scope, departments);
        Map<Long, LocationEntity> locations = locationRepository.findAllById(visible.stream()
                        .map(EmployeeEntity::getKioskLocationId).filter(java.util.Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(LocationEntity::getId, Function.identity()));
        int holidayYear = year == null ? GuatemalaDateTime.today().getYear() : year;
        BigDecimal overtime = PayrollParameters.DEFAULT_OVERTIME_FACTOR;
        BigDecimal holiday = PayrollParameters.DEFAULT_HOLIDAY_FACTOR;
        BigDecimal workday = PayrollParameters.DEFAULT_WORKDAY_HOURS;
        try {
            PayrollParameters parameters = parameterService.effectiveAt(GuatemalaDateTime.today());
            overtime = parameters.overtimeFactor();
            holiday = parameters.holidayFactor();
            workday = parameters.workdayHours();
        } catch (BusinessException ex) {
            // Sin parámetros cargados rigen los valores de costumbre.
        }
        return PayrollIncidentWorkspaceResponse.builder()
                .sections(new ArrayList<>(scope.sections()))
                .people(visible.stream().map(e -> PayrollIncidentWorkspaceResponse.Person.builder()
                        .id(e.getId())
                        .fullName(EmployeeChargeService.fullName(e))
                        .position(e.getPosition())
                        .departmentName(departments.get(e.getDepartmentId()) == null ? null : departments.get(e.getDepartmentId()).getName())
                        .section(sectionOf(e, departments.get(e.getDepartmentId())))
                        .kioskLocationId(e.getKioskLocationId())
                        .kioskName(e.getKioskLocationId() == null || locations.get(e.getKioskLocationId()) == null ? null
                                : locations.get(e.getKioskLocationId()).getName())
                        .hireDate(e.getHireDate())
                        .build()).toList())
                .holidays(GuatemalaHolidays.forYear(holidayYear).stream().map(h -> PayrollIncidentWorkspaceResponse.Holiday.builder()
                        .date(h.date()).name(h.name()).halfDay(h.halfDay()).cityOnly(h.cityOnly()).build()).toList())
                .overtimeFactor(overtime)
                .holidayFactor(holiday)
                .workdayHours(workday)
                .build();
    }

    /** Las novedades activas que tocan el rango y son de personas que el usuario puede ver. */
    @Transactional(readOnly = true)
    public List<PayrollIncidentResponse> list(LocalDate from, LocalDate to) {
        Scope scope = scope();
        LocalDate start = from == null ? GuatemalaDateTime.today().withDayOfMonth(1) : from;
        LocalDate end = to == null ? start.plusMonths(1).minusDays(1) : to;
        Map<Long, DepartmentEntity> departments = departmentRepository.findAll().stream()
                .collect(Collectors.toMap(DepartmentEntity::getId, Function.identity()));
        Map<Long, EmployeeEntity> visible = visibleEmployees(scope, departments).stream()
                .collect(Collectors.toMap(EmployeeEntity::getId, Function.identity()));
        return incidentRepository.findActiveBetween(start, end).stream()
                .filter(i -> visible.containsKey(i.getEmployeeId()))
                .map(i -> toResponse(i, visible.get(i.getEmployeeId()), departments))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public List<PayrollIncidentResponse> create(PayrollIncidentRequest request) throws BusinessException {
        Scope scope = scope();
        String kind = PayrollRules.normalize(request.getKind());
        if (kind == null || !KINDS.contains(kind)) {
            throw new BusinessException("Tipo de novedad inválido. Use HORA_EXTRA, FERIADO_TRABAJADO, AUSENCIA o VACACIONES.");
        }
        boolean ranged = PayrollIncidentEntity.KIND_ABSENCE.equals(kind) || PayrollIncidentEntity.KIND_VACATION.equals(kind);
        LocalDate from = request.getDateFrom();
        LocalDate to = ranged ? (request.getDateTo() == null ? from : request.getDateTo()) : from;
        if (to.isBefore(from)) {
            throw new BusinessException("La fecha final no puede ser anterior a la inicial.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new BusinessException("Una novedad puede abarcar hasta " + MAX_RANGE_DAYS + " días; sepárela en varias.");
        }
        BigDecimal hours = request.getHours();
        BigDecimal rate = request.getHourlyRate();
        String holidayName = null;
        LocalDate today = GuatemalaDateTime.today();
        switch (kind) {
            case PayrollIncidentEntity.KIND_OVERTIME -> {
                if (hours == null || hours.signum() <= 0 || hours.compareTo(BigDecimal.valueOf(16)) > 0) {
                    throw new BusinessException("Indique las horas extra: más de 0 y hasta 16.");
                }
                if (from.isAfter(today)) {
                    throw new BusinessException("Las horas extra se registran cuando ya se trabajaron: la fecha no puede ser futura.");
                }
            }
            case PayrollIncidentEntity.KIND_HOLIDAY_WORKED -> {
                holidayName = GuatemalaHolidays.nameOf(from);
                if (holidayName == null) {
                    throw new BusinessException("El " + from + " no es feriado en Guatemala.");
                }
                if (from.isAfter(today)) {
                    throw new BusinessException("El feriado trabajado se registra cuando ya se trabajó: la fecha no puede ser futura.");
                }
                if (hours != null && (hours.signum() <= 0 || hours.compareTo(BigDecimal.valueOf(24)) > 0)) {
                    throw new BusinessException("Las horas del feriado deben ser más de 0 y hasta 24.");
                }
            }
            default -> {
                hours = null;
                rate = null;
            }
        }
        if (rate != null && rate.signum() < 0) {
            throw new BusinessException("El pago por hora no puede ser negativo.");
        }

        Map<Long, DepartmentEntity> departments = departmentRepository.findAll().stream()
                .collect(Collectors.toMap(DepartmentEntity::getId, Function.identity()));
        Map<Long, EmployeeEntity> visible = visibleEmployees(scope, departments).stream()
                .collect(Collectors.toMap(EmployeeEntity::getId, Function.identity()));
        List<PayrollIncidentEntity> created = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long employeeId : request.getEmployeeIds()) {
            if (!seen.add(employeeId)) {
                continue;
            }
            EmployeeEntity employee = visible.get(employeeId);
            if (employee == null) {
                throw new AccessDeniedException("No puede registrar novedades de una de las personas elegidas.");
            }
            String name = EmployeeChargeService.fullName(employee);
            assertPeriodsOpen(employee, from, to);
            assertNoDuplicate(employeeId, name, kind, from, to);
            created.add(incidentRepository.save(PayrollIncidentEntity.builder()
                    .employeeId(employeeId)
                    .kind(kind)
                    .dateFrom(from)
                    .dateTo(to)
                    .hours(hours)
                    .hourlyRateOverride(rate)
                    .holidayName(holidayName)
                    .note(trimToNull(request.getNote()))
                    .createdBy(accessGuard.currentUserId())
                    .build()));
        }
        return created.stream().map(i -> toResponse(i, visible.get(i.getEmployeeId()), departments)).toList();
    }

    /** Anula una novedad que el usuario puede ver, mientras la planilla de su período no esté aprobada. */
    @Transactional(rollbackFor = Exception.class)
    public PayrollIncidentResponse cancel(Long id) throws BusinessException, ResourceNotFoundException {
        Scope scope = scope();
        PayrollIncidentEntity incident = incidentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PayrollIncident", id));
        Map<Long, DepartmentEntity> departments = departmentRepository.findAll().stream()
                .collect(Collectors.toMap(DepartmentEntity::getId, Function.identity()));
        EmployeeEntity employee = visibleEmployees(scope, departments).stream()
                .filter(e -> e.getId().equals(incident.getEmployeeId())).findFirst().orElse(null);
        if (employee == null) {
            // Tampoco se revela si existe: para quien no la ve, no existe.
            throw new ResourceNotFoundException("PayrollIncident", id);
        }
        if (!PayrollIncidentEntity.STATUS_ACTIVE.equals(incident.getStatus())) {
            throw new BusinessException("La novedad ya está anulada.");
        }
        assertPeriodsOpen(employee, incident.getDateFrom(), incident.getDateTo());
        incident.setStatus(PayrollIncidentEntity.STATUS_CANCELLED);
        incident.setCancelledBy(accessGuard.currentUserId());
        incident.setCancelledAt(GuatemalaDateTime.now());
        return toResponse(incidentRepository.save(incident), employee, departments);
    }

    /** No se registra ni se anula nada que caiga en una planilla ya aprobada o pagada: no cambiaría el pago. */
    private void assertPeriodsOpen(EmployeeEntity employee, LocalDate from, LocalDate to) throws BusinessException {
        Set<LocalDate> checked = new HashSet<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            PayrollCalendar.PayDay payDay = PayrollCalendar.payDayCovering(employee.getPayGroup(), day);
            if (payDay == null || !checked.add(payDay.scheduledDate())) {
                continue;
            }
            PayrollRunEntity run = runRepository.findActive(employee.getPayGroup(), payDay.scheduledDate()).orElse(null);
            if (run != null && (PayrollRunService.STATUS_APPROVED.equals(run.getStatus()) || PayrollRunService.STATUS_PAID.equals(run.getStatus()))) {
                throw new BusinessException("La planilla #" + run.getId() + " (" + payDay.periodStart() + " al " + payDay.periodEnd()
                        + ") ya está " + run.getStatus().toLowerCase() + ": un aviso de esas fechas no cambiaría el pago de "
                        + EmployeeChargeService.fullName(employee) + ".");
            }
        }
    }

    private void assertNoDuplicate(Long employeeId, String name, String kind, LocalDate from, LocalDate to) throws BusinessException {
        boolean ranged = PayrollIncidentEntity.KIND_ABSENCE.equals(kind) || PayrollIncidentEntity.KIND_VACATION.equals(kind);
        for (PayrollIncidentEntity existing : incidentRepository.findActiveOfEmployeeBetween(employeeId, from, to)) {
            boolean existingRanged = PayrollIncidentEntity.KIND_ABSENCE.equals(existing.getKind())
                    || PayrollIncidentEntity.KIND_VACATION.equals(existing.getKind());
            // Una ausencia y unas vacaciones no pueden cruzarse; una hora extra puede caer en un día cualquiera.
            if (ranged && existingRanged) {
                throw new BusinessException(name + " ya tiene " + existing.getKind().toLowerCase() + " del " + existing.getDateFrom()
                        + " al " + existing.getDateTo() + " que se cruza con estas fechas.");
            }
            if (PayrollIncidentEntity.KIND_HOLIDAY_WORKED.equals(kind) && kind.equals(existing.getKind())) {
                throw new BusinessException(name + " ya tiene registrado el feriado trabajado del " + from + ".");
            }
        }
    }

    private PayrollIncidentResponse toResponse(PayrollIncidentEntity incident, EmployeeEntity employee,
                                               Map<Long, DepartmentEntity> departments) {
        return PayrollIncidentResponse.builder()
                .id(incident.getId())
                .employeeId(incident.getEmployeeId())
                .employeeName(employee == null ? "Empleado #" + incident.getEmployeeId() : EmployeeChargeService.fullName(employee))
                .section(employee == null ? null : sectionOf(employee, departments.get(employee.getDepartmentId())))
                .kind(incident.getKind())
                .dateFrom(incident.getDateFrom())
                .dateTo(incident.getDateTo())
                .hours(incident.getHours())
                .hourlyRateOverride(incident.getHourlyRateOverride())
                .holidayName(incident.getHolidayName())
                .note(incident.getNote())
                .createdAt(incident.getCreatedAt())
                .build();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
