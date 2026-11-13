package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.PayrollRunCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollRunResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DepartmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeChargeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollParameterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.DepartmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeChargeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayrollParameterRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las pantallas de planilla y empleados no deben hacer una consulta por fila: la cantidad de consultas tiene que
 * mantenerse igual aunque haya 30 colaboradores o 30 cargos.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payroll_queries;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class PayrollEndpointQueryCountTest {
    static final int PEOPLE = 30;

    @Autowired PayrollRunService runs;
    @Autowired EmployeeChargeService chargeService;
    @Autowired EmployeeService employeeService;
    @Autowired PayrollProfileService profileService;
    @Autowired EmployeeRepository employees;
    @Autowired DepartmentRepository departments;
    @Autowired EmployeeChargeRepository charges;
    @Autowired PayrollParameterRepository parameters;
    @Autowired UserRepository users;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired EntityManager entityManager;
    @MockitoBean PayrollAccessGuard guard;

    Long runId;

    @BeforeEach
    void fixture() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        parameters.saveAll(List.of(
                param(PayrollParameters.IGSS_LABORAL_PCT, "4.83", from), param(PayrollParameters.IGSS_PATRONAL_PCT, "10.67", from),
                param(PayrollParameters.IRTRA_PCT, "1", from), param(PayrollParameters.INTECAP_PCT, "1", from),
                param(PayrollParameters.BONIFICACION_INCENTIVO_MENSUAL, "250", from),
                param(PayrollParameters.ISR_DEDUCCION_PERSONAL, "48000", from), param(PayrollParameters.ISR_TRAMO_LIMITE, "300000", from),
                param(PayrollParameters.ISR_TASA_TRAMO1_PCT, "5", from), param(PayrollParameters.ISR_TASA_TRAMO2_PCT, "7", from),
                param(PayrollParameters.ISR_CUOTA_FIJA_TRAMO2, "15000", from)));
        DepartmentEntity production = departments.save(DepartmentEntity.builder()
                .code("P" + UUID.randomUUID().toString().substring(0, 6)).name("Producción").build());
        for (int i = 0; i < PEOPLE; i++) {
            UserEntity user = users.save(UserEntity.builder().username("u" + i + UUID.randomUUID().toString().substring(0, 5))
                    .email("u" + i + UUID.randomUUID().toString().substring(0, 5) + "@prueba.com").password("x").status("active").build());
            EmployeeEntity employee = EmployeeEntity.builder().firstName("Persona" + i).lastName("Prueba").salary(new BigDecimal("4000"))
                    .payGroup("PLANTA").employmentType("PLANILLA").paymentMethod("CHEQUE").departmentId(production.getId())
                    .status("active").users(new HashSet<>(List.of(user))).build();
            employee = employees.save(employee);
            charges.save(EmployeeChargeEntity.builder().employeeId(employee.getId()).sourceType("PRUEBA").sourceId((long) i)
                    .description("Compra").amount(new BigDecimal("100")).balance(new BigDecimal("100")).settlementMethod("DESCUENTO")
                    .chargedOn(LocalDate.of(2026, 10, 5)).build());
        }
        PayrollRunCreateRequest request = new PayrollRunCreateRequest();
        request.setPayGroup("PLANTA");
        request.setScheduledDate(LocalDate.of(2026, 10, 20));
        PayrollRunResponse run = runs.create(request);
        runId = run.getId();
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void chargesListDoesNotQueryPerCharge() throws Exception {
        long count = statements(() -> chargeService.list(null, null));
        assertThat(count).isLessThanOrEqualTo(5);
    }

    @Test
    void payDaysDoNotQueryPerDay() throws Exception {
        long count = statements(() -> runs.payDays(null, 6));
        assertThat(count).isLessThanOrEqualTo(3);
    }

    @Test
    void aDraftRunDetailDoesNotQueryPerPersonOrCharge() throws Exception {
        long count = statements(() -> runs.get(runId));
        assertThat(count).isLessThanOrEqualTo(15);
    }

    @Test
    void payrollProfilesDoNotQueryPerPerson() throws Exception {
        long count = statements(() -> profileService.list());
        assertThat(count).isLessThanOrEqualTo(4);
    }

    @Test
    void employeesListDoesNotQueryPerPerson() throws Exception {
        long count = statements(() -> employeeService.getAllEmployees());
        assertThat(count).isLessThanOrEqualTo(4);
    }

    interface Call {
        Object run() throws Exception;
    }

    private long statements(Call call) throws Exception {
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        call.run();
        return statistics.getPrepareStatementCount();
    }

    private static PayrollParameterEntity param(String code, String value, LocalDate from) {
        return PayrollParameterEntity.builder().code(code).value(new BigDecimal(value)).effectiveFrom(from).build();
    }
}
