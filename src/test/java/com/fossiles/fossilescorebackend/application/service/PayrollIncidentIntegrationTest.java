package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.PayrollIncidentRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollRunCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollIncidentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollIncidentWorkspaceResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollRunResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DepartmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSupervisorAssignmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollParameterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.DepartmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSupervisorAssignmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayrollParameterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Novedades de personal: quién ve y registra a quién, y cómo cambian la planilla (horas extra, feriados, ausencias).
 * Planta paga el 20 de octubre el período del 6 al 20.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:payroll_incidents;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class PayrollIncidentIntegrationTest {
    static final LocalDate PLANT_20_OCT = LocalDate.of(2026, 10, 20);
    static final Long SUPERVISOR_USER = 900L;

    @Autowired PayrollIncidentService incidents;
    @Autowired PayrollRunService runs;
    @Autowired EmployeeRepository employees;
    @Autowired DepartmentRepository departments;
    @Autowired LocationRepository locations;
    @Autowired KioskSupervisorAssignmentRepository assignments;
    @Autowired PayrollParameterRepository parameters;
    @MockitoBean PayrollAccessGuard guard;

    EmployeeEntity operator;
    EmployeeEntity sellerA;
    EmployeeEntity sellerB;
    EmployeeEntity accountant;

    @BeforeEach
    void fixture() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        parameters.saveAll(List.of(
                param(PayrollParameters.IGSS_LABORAL_PCT, "4.83", from), param(PayrollParameters.IGSS_PATRONAL_PCT, "10.67", from),
                param(PayrollParameters.IRTRA_PCT, "1", from), param(PayrollParameters.INTECAP_PCT, "1", from),
                param(PayrollParameters.BONIFICACION_INCENTIVO_MENSUAL, "250", from),
                param(PayrollParameters.ISR_DEDUCCION_PERSONAL, "48000", from), param(PayrollParameters.ISR_TRAMO_LIMITE, "300000", from),
                param(PayrollParameters.ISR_TASA_TRAMO1_PCT, "5", from), param(PayrollParameters.ISR_TASA_TRAMO2_PCT, "7", from),
                param(PayrollParameters.ISR_CUOTA_FIJA_TRAMO2, "15000", from)));
        DepartmentEntity production = departments.save(DepartmentEntity.builder().code(code("P")).name("Producción").build());
        DepartmentEntity kiosk = departments.save(DepartmentEntity.builder().code(code("K")).name("Kiosco Miraflores").build());
        DepartmentEntity office = departments.save(DepartmentEntity.builder().code(code("A")).name("Contabilidad").build());
        LocationEntity kioskA = locations.save(LocationEntity.builder().code("KA").name("Kiosco A").build());
        LocationEntity kioskB = locations.save(LocationEntity.builder().code("KB").name("Kiosco B").build());
        operator = employee("Ana", "4000", "PLANTA", production, null);
        sellerA = employee("Vera", "3000", "ADMINISTRACION", kiosk, kioskA.getId());
        sellerB = employee("Beto", "3000", "ADMINISTRACION", kiosk, kioskB.getId());
        accountant = employee("Carla", "6000", "ADMINISTRACION", office, null);
        assignments.save(KioskSupervisorAssignmentEntity.builder()
                .supervisorUserId(SUPERVISOR_USER).kioskLocationId(kioskA.getId()).createdByUserId(1L).build());
    }

    private void asSupervisor() {
        when(guard.incidentSections()).thenReturn(Set.of("KIOSCOS"));
        when(guard.isAdministrator()).thenReturn(false);
        when(guard.currentUserId()).thenReturn(SUPERVISOR_USER);
    }

    private void asAdministrator() {
        when(guard.incidentSections()).thenReturn(Set.of("PLANTA", "KIOSCOS", "ADMINISTRACION"));
        when(guard.isAdministrator()).thenReturn(true);
        when(guard.currentUserId()).thenReturn(1L);
        when(guard.canViewSalaries()).thenReturn(true);
    }

    @Test
    void aSupervisorOnlySeesAndRegistersThePeopleOfHerKiosks() throws Exception {
        asSupervisor();
        PayrollIncidentWorkspaceResponse workspace = incidents.workspace(2026);
        assertThat(workspace.getSections()).containsExactly("KIOSCOS");
        assertThat(workspace.getPeople()).extracting(PayrollIncidentWorkspaceResponse.Person::getId).containsExactly(sellerA.getId());
        assertThat(workspace.getPeople().get(0).getKioskName()).isEqualTo("Kiosco A");
        assertThat(workspace.getHolidays()).extracting(PayrollIncidentWorkspaceResponse.Holiday::getName).contains("Día de la Independencia");

        List<PayrollIncidentResponse> created = incidents.create(overtime(List.of(sellerA.getId()), LocalDate.of(2026, 10, 10), "2"));
        assertThat(created).singleElement().satisfies(i -> assertThat(i.getSection()).isEqualTo("KIOSCOS"));

        assertThatThrownBy(() -> incidents.create(overtime(List.of(sellerB.getId()), LocalDate.of(2026, 10, 10), "2")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> incidents.create(overtime(List.of(operator.getId()), LocalDate.of(2026, 10, 10), "2")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(incidents.list(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))).hasSize(1);
    }

    @Test
    void anAdministratorSeesEveryone() {
        asAdministrator();
        assertThat(incidents.workspace(2026).getPeople()).extracting(PayrollIncidentWorkspaceResponse.Person::getSection)
                .containsExactlyInAnyOrder("PLANTA", "KIOSCOS", "KIOSCOS", "ADMINISTRACION");
    }

    @Test
    void withoutAnySectionNobodyCanUseIt() {
        when(guard.incidentSections()).thenReturn(Set.of());
        assertThatThrownBy(() -> incidents.workspace(2026)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> incidents.list(null, null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void overtimeAndWorkedHolidayAddToThePayrollAndToTheIgssBase() throws Exception {
        asAdministrator();
        incidents.create(overtime(List.of(operator.getId()), LocalDate.of(2026, 10, 10), "2"));
        PayrollIncidentRequest holiday = new PayrollIncidentRequest();
        holiday.setEmployeeIds(List.of(operator.getId()));
        holiday.setKind("FERIADO_TRABAJADO");
        holiday.setDateFrom(LocalDate.of(2026, 10, 20));
        incidents.create(holiday);

        PayrollRunResponse run = runs.create(plantRun());
        PayrollRunResponse.Line line = run.getLines().get(0);
        // Valor hora 4000 / 30 / 8 = 16.6667. Dos horas extra a 1.5 = 50.00; el feriado, 8 horas al doble = 266.67.
        assertThat(line.getExtraPay()).isEqualByComparingTo("316.67");
        assertThat(line.getBaseSalary()).isEqualByComparingTo("2000.00");
        assertThat(line.getIgssEmployee()).isGreaterThan(new BigDecimal("96.60"));
        assertThat(run.getTotalGross()).isEqualByComparingTo("2441.67");
    }

    @Test
    void aSpecialHourlyRateReplacesTheSurcharge() throws Exception {
        asAdministrator();
        PayrollIncidentRequest request = overtime(List.of(operator.getId()), LocalDate.of(2026, 10, 10), "3");
        request.setHourlyRate(new BigDecimal("40"));
        incidents.create(request);
        PayrollRunResponse.Line line = runs.create(plantRun()).getLines().get(0);
        assertThat(line.getExtraPay()).isEqualByComparingTo("120.00");
    }

    @Test
    void unpaidAbsenceTakesDaysOffButVacationDoesNot() throws Exception {
        asAdministrator();
        PayrollIncidentRequest absence = ranged("AUSENCIA", operator, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14));
        incidents.create(absence);
        PayrollIncidentRequest vacation = ranged("VACACIONES", operator, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));
        incidents.create(vacation);

        PayrollRunResponse.Line line = runs.create(plantRun()).getLines().get(0);
        assertThat(line.getAbsenceDays()).isEqualByComparingTo("3");
        assertThat(line.getVacationDays()).isEqualByComparingTo("2");
        assertThat(line.getDaysPaid()).isEqualByComparingTo("12.00");
        assertThat(line.getBaseSalary()).isEqualByComparingTo("1600.00");
    }

    @Test
    void aNewIncidentMakesTheDraftStaleAndRecalculating_picksItUp() throws Exception {
        asAdministrator();
        PayrollRunResponse draft = runs.create(plantRun());
        assertThat(draft.getStale()).isFalse();
        incidents.create(overtime(List.of(operator.getId()), LocalDate.of(2026, 10, 10), "2"));
        assertThat(runs.get(draft.getId()).getStale()).isTrue();
        assertThat(runs.recalculate(draft.getId()).getLines().get(0).getExtraPay()).isEqualByComparingTo("50.00");
    }

    @Test
    void nothingIsRegisteredOrCancelledInsideAnApprovedPayroll() throws Exception {
        asAdministrator();
        PayrollIncidentResponse incident = incidents.create(overtime(List.of(operator.getId()), LocalDate.of(2026, 10, 10), "2")).get(0);
        PayrollRunResponse run = runs.create(plantRun());
        runs.approve(run.getId());

        assertThatThrownBy(() -> incidents.create(overtime(List.of(operator.getId()), LocalDate.of(2026, 10, 11), "1")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya está aprobada");
        assertThatThrownBy(() -> incidents.cancel(incident.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya está aprobada");
    }

    @Test
    void cancellingRemovesItFromThePayroll() throws Exception {
        asAdministrator();
        PayrollIncidentResponse incident = incidents.create(overtime(List.of(operator.getId()), LocalDate.of(2026, 10, 10), "2")).get(0);
        incidents.cancel(incident.getId());
        assertThat(incidents.list(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))).isEmpty();
        assertThat(runs.create(plantRun()).getLines().get(0).getExtraPay()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> incidents.cancel(incident.getId())).isInstanceOf(BusinessException.class).hasMessageContaining("anulada");
    }

    @Test
    void invalidIncidentsAreRejectedWithAClearMessage() {
        asAdministrator();
        PayrollIncidentRequest notHoliday = new PayrollIncidentRequest();
        notHoliday.setEmployeeIds(List.of(operator.getId()));
        notHoliday.setKind("FERIADO_TRABAJADO");
        notHoliday.setDateFrom(LocalDate.of(2026, 10, 7));
        assertThatThrownBy(() -> incidents.create(notHoliday)).isInstanceOf(BusinessException.class).hasMessageContaining("no es feriado");

        assertThatThrownBy(() -> incidents.create(overtime(List.of(operator.getId()), LocalDate.of(2026, 10, 7), "0")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("horas extra");
        assertThatThrownBy(() -> incidents.create(overtime(List.of(operator.getId()), LocalDate.now().plusDays(3), "2")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("futura");
        assertThatThrownBy(() -> incidents.create(ranged("AUSENCIA", operator, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 12))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("anterior");
        PayrollIncidentRequest unknown = overtime(List.of(operator.getId()), LocalDate.of(2026, 10, 7), "2");
        unknown.setKind("REGALO");
        assertThatThrownBy(() -> incidents.create(unknown)).isInstanceOf(BusinessException.class).hasMessageContaining("Tipo de novedad");
    }

    @Test
    void overlappingAbsencesAndVacationsOfTheSamePersonAreRejected() throws Exception {
        asAdministrator();
        incidents.create(ranged("AUSENCIA", operator, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14)));
        assertThatThrownBy(() -> incidents.create(ranged("VACACIONES", operator, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 16))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("se cruza");
    }

    private EmployeeEntity employee(String firstName, String salary, String payGroup, DepartmentEntity department, Long kioskLocationId) {
        return employees.save(EmployeeEntity.builder()
                .firstName(firstName).lastName("Prueba").salary(new BigDecimal(salary))
                .payGroup(payGroup).employmentType("PLANILLA").paymentMethod("CHEQUE")
                .departmentId(department.getId()).kioskLocationId(kioskLocationId).status("active").build());
    }

    private static PayrollIncidentRequest overtime(List<Long> ids, LocalDate date, String hours) {
        PayrollIncidentRequest request = new PayrollIncidentRequest();
        request.setEmployeeIds(ids);
        request.setKind("HORA_EXTRA");
        request.setDateFrom(date);
        request.setHours(new BigDecimal(hours));
        return request;
    }

    private static PayrollIncidentRequest ranged(String kind, EmployeeEntity employee, LocalDate from, LocalDate to) {
        PayrollIncidentRequest request = new PayrollIncidentRequest();
        request.setEmployeeIds(List.of(employee.getId()));
        request.setKind(kind);
        request.setDateFrom(from);
        request.setDateTo(to);
        return request;
    }

    private static PayrollRunCreateRequest plantRun() {
        PayrollRunCreateRequest request = new PayrollRunCreateRequest();
        request.setPayGroup("PLANTA");
        request.setScheduledDate(PLANT_20_OCT);
        return request;
    }

    private static PayrollParameterEntity param(String code, String value, LocalDate from) {
        return PayrollParameterEntity.builder().code(code).value(new BigDecimal(value)).effectiveFrom(from).build();
    }

    private static String code(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }
}
