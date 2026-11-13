package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.EmployeeChargeCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollRunCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.EmployeeChargeResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollRunResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.AccountingEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DepartmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeChargeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollParameterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.AccountingEntryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.DepartmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeChargeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

/** Cargos manuales a colaboradores: descuadres, faltantes de caja y daños, con su partida y su cuota por quincena. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:payroll_manual;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class ManualEmployeeChargeIntegrationTest {
    static final LocalDate PLANT_20_OCT = LocalDate.of(2026, 10, 20);
    static final LocalDate PLANT_5_NOV = LocalDate.of(2026, 11, 5);

    @Autowired PayrollRunService runs;
    @Autowired EmployeeChargeService chargeService;
    @Autowired EmployeeRepository employees;
    @Autowired DepartmentRepository departments;
    @Autowired EmployeeChargeRepository charges;
    @Autowired PayrollParameterRepository parameters;
    @Autowired AccountingEntryRepository entries;
    @MockitoBean PayrollAccessGuard guard;

    EmployeeEntity operator;
    EmployeeEntity clerk;

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
        DepartmentEntity shop = departments.save(DepartmentEntity.builder().code(code("A")).name("Administración").build());
        operator = employee("Ana", "4000", "PLANTA", production);
        clerk = employee("Carla", "6000", "ADMINISTRACION", shop);
    }

    @Test
    void aManualChargeToPlantGoesToPayrollAndPostsItsEntry() throws Exception {
        EmployeeChargeResponse plant = chargeService.createManual(request(operator, "DESCUADRE_INVENTARIO", "250.00"));
        assertThat(plant.getSourceType()).isEqualTo("MANUAL");
        assertThat(plant.getSettlementMethod()).isEqualTo("DESCUENTO");
        assertThat(plant.getStatus()).isEqualTo("PENDIENTE");
        assertThat(plant.getConcept()).isEqualTo("DESCUADRE_INVENTARIO");
        assertThat(plant.getDescription()).startsWith("Descuadre de inventario: ");

        EmployeeChargeResponse admin = chargeService.createManual(request(clerk, "FALTANTE_CAJA", "75.50"));
        assertThat(admin.getSettlementMethod()).isNull();

        List<AccountingEntryEntity> posted = entries.findByDocumentTypeAndDocumentId("EMPLOYEE_CHARGE", plant.getId());
        assertThat(posted).hasSize(2);
        assertThat(sum(posted, "1.1.2.05", true)).isEqualByComparingTo("250.00");
        assertThat(sum(posted, "4.2.1.03", false)).isEqualByComparingTo("250.00");

        chargeService.cancel(plant.getId());
        assertThat(entries.findByDocumentTypeAndDocumentId("EMPLOYEE_CHARGE_CANCELLATION", plant.getId())).hasSize(2);
    }

    @Test
    void theChargeNeedsAuthorizationReasonAndSensibleAmounts() {
        EmployeeChargeCreateRequest noAcceptance = request(operator, "FALTANTE_CAJA", "100");
        noAcceptance.setEmployeeAuthorized(false);
        assertThatThrownBy(() -> chargeService.createManual(noAcceptance))
                .isInstanceOf(BusinessException.class).hasMessageContaining("aceptación del colaborador");

        EmployeeChargeCreateRequest noReason = request(operator, "FALTANTE_CAJA", "100");
        noReason.setReason("   ");
        assertThatThrownBy(() -> chargeService.createManual(noReason))
                .isInstanceOf(BusinessException.class).hasMessageContaining("motivo");

        assertThatThrownBy(() -> chargeService.createManual(request(operator, "REGALO", "100")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Concepto inválido");
        assertThatThrownBy(() -> chargeService.createManual(request(operator, "OTRO", "0")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("mayor que cero");

        EmployeeChargeCreateRequest bigInstallment = request(operator, "OTRO", "100");
        bigInstallment.setInstallmentAmount(new BigDecimal("150"));
        assertThatThrownBy(() -> chargeService.createManual(bigInstallment))
                .isInstanceOf(BusinessException.class).hasMessageContaining("cuota");

        EmployeeChargeCreateRequest future = request(operator, "OTRO", "100");
        future.setChargedOn(LocalDate.now().plusDays(3));
        assertThatThrownBy(() -> chargeService.createManual(future))
                .isInstanceOf(BusinessException.class).hasMessageContaining("futura");
        assertThat(charges.findAll()).isEmpty();
    }

    @Test
    void whoLacksThePermissionCannotRegisterCharges() {
        doThrow(new AccessDeniedException("No tiene permiso")).when(guard).assertCanCreateCharges();
        assertThatThrownBy(() -> chargeService.createManual(request(operator, "OTRO", "100")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(charges.findAll()).isEmpty();
    }

    @Test
    void anInstallmentCapsWhatEachPayrollDeducts() throws Exception {
        EmployeeChargeCreateRequest big = request(operator, "DESCUADRE_INVENTARIO", "1000");
        big.setInstallmentAmount(new BigDecimal("200"));
        big.setChargedOn(LocalDate.of(2026, 10, 5));
        EmployeeChargeResponse created = chargeService.createManual(big);

        PayrollRunResponse october = runs.create(run("PLANTA", PLANT_20_OCT));
        assertThat(october.getLines()).singleElement()
                .satisfies(line -> assertThat(line.getOpiDiscount()).isEqualByComparingTo("200.00"));
        runs.approve(october.getId());
        assertThat(charges.findById(created.getId()).orElseThrow().getBalance()).isEqualByComparingTo("800.00");

        PayrollRunResponse november = runs.create(run("PLANTA", PLANT_5_NOV));
        assertThat(november.getLines()).singleElement()
                .satisfies(line -> assertThat(line.getOpiDiscount()).isEqualByComparingTo("200.00"));
    }

    @Test
    void withoutInstallmentThePayrollDeductsWhatFits() throws Exception {
        EmployeeChargeCreateRequest request = request(operator, "FALTANTE_CAJA", "300");
        request.setChargedOn(LocalDate.of(2026, 10, 5));
        chargeService.createManual(request);
        PayrollRunResponse october = runs.create(run("PLANTA", PLANT_20_OCT));
        assertThat(october.getLines()).singleElement()
                .satisfies(line -> assertThat(line.getOpiDiscount()).isEqualByComparingTo("300.00"));
    }

    private EmployeeEntity employee(String firstName, String salary, String payGroup, DepartmentEntity department) {
        return employees.save(EmployeeEntity.builder()
                .firstName(firstName).lastName("Prueba").salary(new BigDecimal(salary))
                .payGroup(payGroup).employmentType("PLANILLA").paymentMethod("CHEQUE")
                .departmentId(department.getId()).status("active").build());
    }

    private static EmployeeChargeCreateRequest request(EmployeeEntity employee, String concept, String amount) {
        EmployeeChargeCreateRequest request = new EmployeeChargeCreateRequest();
        request.setEmployeeId(employee.getId());
        request.setConcept(concept);
        request.setAmount(new BigDecimal(amount));
        request.setReason("Conteo del kiosco del 10 de noviembre: faltaron 2 billeteras");
        request.setReference("Conteo #85");
        request.setEmployeeAuthorized(true);
        request.setAuthorizationNote("Carta firmada, archivo de Contabilidad");
        return request;
    }

    private static PayrollRunCreateRequest run(String group, LocalDate date) {
        PayrollRunCreateRequest request = new PayrollRunCreateRequest();
        request.setPayGroup(group);
        request.setScheduledDate(date);
        return request;
    }

    private static PayrollParameterEntity param(String code, String value, LocalDate from) {
        return PayrollParameterEntity.builder().code(code).value(new BigDecimal(value)).effectiveFrom(from).build();
    }

    private static BigDecimal sum(List<AccountingEntryEntity> posted, String account, boolean debit) {
        return posted.stream().filter(e -> account.equals(e.getAccountCode()))
                .map(e -> debit ? e.getDebitAmount() : e.getCreditAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String code(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }
}
