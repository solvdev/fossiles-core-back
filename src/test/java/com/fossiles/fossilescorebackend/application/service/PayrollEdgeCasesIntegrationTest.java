package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.EmployeeRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollChequeAssignRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollParameterRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollRunCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.EmployeeResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollParameterResponse;
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
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeSalaryHistoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayrollParameterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:payroll_edges;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class PayrollEdgeCasesIntegrationTest {
    static final LocalDate PLANT_20_OCT = LocalDate.of(2026, 10, 20);
    static final LocalDate PLANT_5_NOV = LocalDate.of(2026, 11, 5);

    @Autowired PayrollRunService runs;
    @Autowired PayrollParameterService parameterService;
    @Autowired EmployeeChargeService chargeService;
    @Autowired PayrollAutoDraftJob autoDraft;
    @Autowired EmployeeService employeeService;
    @Autowired EmployeeRepository employees;
    @Autowired DepartmentRepository departments;
    @Autowired EmployeeChargeRepository charges;
    @Autowired PayrollParameterRepository parameters;
    @Autowired AccountingEntryRepository entries;
    @Autowired EmployeeSalaryHistoryRepository salaryHistory;
    @MockitoBean PayrollAccessGuard guard;

    DepartmentEntity production;
    DepartmentEntity office;
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
        production = departments.save(DepartmentEntity.builder().code(code("P")).name("Producción").build());
        office = departments.save(DepartmentEntity.builder().code(code("A")).name("Administración").build());
        operator = employee("Ana", "4000", "PLANTA", "PLANILLA", "CHEQUE", production);
        clerk = employee("Carla", "6000", "ADMINISTRACION", "PLANILLA", "TRANSFERENCIA BANCARIA", office);
    }

    @Test
    void onlyPeopleWhoWorkedInThePeriodEnterWithProportionalDays() throws Exception {
        EmployeeEntity inactive = employee("Inactivo", "3000", "PLANTA", "PLANILLA", "CHEQUE", production);
        inactive.setStatus("inactive");
        EmployeeEntity leftBefore = employee("Salio antes", "3000", "PLANTA", "PLANILLA", "CHEQUE", production);
        leftBefore.setTerminationDate(LocalDate.of(2026, 9, 30));
        EmployeeEntity leftDuring = employee("Salio durante", "3000", "PLANTA", "PLANILLA", "CHEQUE", production);
        leftDuring.setTerminationDate(LocalDate.of(2026, 10, 10));
        leftDuring.setStatus("inactive");
        EmployeeEntity noSalary = employee("Sin salario", "0", "PLANTA", "PLANILLA", "CHEQUE", production);
        EmployeeEntity notYet = employee("Entra despues", "3000", "PLANTA", "PLANILLA", "CHEQUE", production);
        notYet.setHireDate(LocalDate.of(2026, 10, 16));
        EmployeeEntity newContractor = employee("Servicios nuevo", "3000", "PLANTA", "SERVICIOS", "EFECTIVO", production);
        newContractor.setHireDate(LocalDate.of(2026, 10, 6));
        employees.saveAll(List.of(inactive, leftBefore, leftDuring, noSalary, notYet, newContractor));

        PayrollRunResponse run = runs.create(request("PLANTA", PLANT_20_OCT));

        assertThat(run.getLines()).extracting(PayrollRunResponse.Line::getEmployeeId)
                .containsExactlyInAnyOrder(operator.getId(), leftDuring.getId(), newContractor.getId());
        PayrollRunResponse.Line during = line(run, leftDuring);
        assertThat(during.getDaysPaid()).isEqualByComparingTo("10.00");
        assertThat(during.getBaseSalary()).isEqualByComparingTo("1000.00");
        assertThat(during.getBonification()).isEqualByComparingTo("83.33");
        PayrollRunResponse.Line contractor = line(run, newContractor);
        assertThat(contractor.getDaysPaid()).isEqualByComparingTo("10.00");
        assertThat(contractor.getBaseSalary()).isEqualByComparingTo("1000.00");
        assertThat(contractor.getBonification()).isEqualByComparingTo("0");
        assertThat(contractor.getIgssEmployee()).isEqualByComparingTo("0");
        assertThat(contractor.getIgssEmployer()).isEqualByComparingTo("0");
        assertThat(contractor.getNetPay()).isEqualByComparingTo("1000.00");
        assertThat(run.getWarnings()).contains("Activos del grupo sin salario en la ficha, que no entran en la planilla: 1");
    }

    @Test
    void twoDraftsOfTheSameGroupNeverDeductTheSameChargeTwice() throws Exception {
        EmployeeChargeEntity purchase = charge(operator, "300", "DESCUENTO");
        PayrollRunResponse october = runs.create(request("PLANTA", PLANT_20_OCT));
        assertThat(line(october, operator).getOpiDiscount()).isEqualByComparingTo("300.00");

        PayrollRunResponse november = runs.create(request("PLANTA", PLANT_5_NOV));
        assertThat(line(november, operator).getOpiDiscount()).isEqualByComparingTo("0");

        runs.annul(october.getId(), "Se rehace");
        assertThat(line(runs.recalculate(november.getId()), operator).getOpiDiscount()).isEqualByComparingTo("300.00");
        assertThat(charges.findById(purchase.getId()).orElseThrow().getBalance()).isEqualByComparingTo("300");
    }

    @Test
    void eachStepOnlyRunsInItsState() throws Exception {
        PayrollRunResponse run = runs.create(request("PLANTA", PLANT_20_OCT));
        assertThatThrownBy(() -> runs.pay(run.getId())).isInstanceOf(BusinessException.class).hasMessageContaining("aprobada");
        assertThatThrownBy(() -> runs.assignCheques(run.getId(), cheques("100")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("aprobada");

        PayrollRunResponse approved = runs.approve(run.getId());
        assertThat(approved.getSuggestedFirstChequeNumber()).isNull();
        assertThatThrownBy(() -> runs.recalculate(run.getId())).isInstanceOf(BusinessException.class).hasMessageContaining("borrador");
        assertThatThrownBy(() -> runs.approve(run.getId())).isInstanceOf(BusinessException.class).hasMessageContaining("borrador");
        assertThatThrownBy(() -> runs.assignCheques(run.getId(), cheques("ABC-1")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("dígitos");
        assertThat(runs.assignCheques(run.getId(), cheques("0099/X")).getLines())
                .extracting(PayrollRunResponse.Line::getChequeNumber).containsExactly("0099/X");

        runs.annul(run.getId(), "Prueba");
        assertThatThrownBy(() -> runs.annul(run.getId(), "Otra vez"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya está anulada");
    }

    @Test
    void cashPaymentsAreCreditedToCashAndBankGetsTheRest() throws Exception {
        EmployeeEntity cashier = employee("Bruno", "3000", "PLANTA", "PLANILLA", "EFECTIVO", production);
        PayrollRunResponse run = runs.create(request("PLANTA", PLANT_20_OCT));
        runs.approve(run.getId());
        runs.assignCheques(run.getId(), cheques("10"));
        runs.pay(run.getId());

        List<AccountingEntryEntity> posted = entries.findByDocumentTypeAndDocumentId("PAYROLL", run.getId());
        assertThat(credited(posted, "1.1.1.01")).isEqualByComparingTo(line(run, cashier).getNetPay());
        assertThat(credited(posted, "1.1.1.02")).isEqualByComparingTo(line(run, operator).getNetPay());
        BigDecimal debit = posted.stream().map(AccountingEntryEntity::getDebitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = posted.stream().map(AccountingEntryEntity::getCreditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).isEqualByComparingTo(credit);
    }

    @Test
    void chargeActionsFollowTheirRules() throws Exception {
        EmployeeChargeEntity plant = charge(operator, "5000", "DESCUENTO");
        assertThatThrownBy(() -> chargeService.decide(plant.getId(), "CHEQUE"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("inválida");
        assertThatThrownBy(() -> chargeService.markPaid(plant.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("se descuentan en planilla");

        PayrollRunResponse run = runs.create(request("PLANTA", PLANT_20_OCT));
        runs.approve(run.getId());
        assertThat(charges.findById(plant.getId()).orElseThrow().getStatus()).isEqualTo("PARCIAL");
        assertThatThrownBy(() -> chargeService.cancel(plant.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya tiene descuentos");

        EmployeeChargeEntity office = charge(clerk, "80", "TRANSFERENCIA");
        chargeService.markPaid(office.getId());
        assertThatThrownBy(() -> chargeService.decide(office.getId(), "DESCUENTO"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("pagado");
    }

    @Test
    void parametersApplyFromTheirDateAndAreValidated() throws Exception {
        PayrollParameterRequest raise = new PayrollParameterRequest();
        raise.setCode("igss_laboral_pct");
        raise.setValue(new BigDecimal("5.00"));
        raise.setEffectiveFrom(LocalDate.of(2099, 1, 1));
        PayrollParameterResponse saved = parameterService.addVersion(raise);
        assertThat(saved.getCode()).isEqualTo("IGSS_LABORAL_PCT");
        assertThat(saved.isCurrent()).isFalse();
        assertThat(parameterService.effectiveAt(LocalDate.of(2098, 12, 31)).igssEmployeePct()).isEqualByComparingTo("4.83");
        assertThat(parameterService.effectiveAt(LocalDate.of(2099, 1, 15)).igssEmployeePct()).isEqualByComparingTo("5.00");

        assertThatThrownBy(() -> parameterService.addVersion(raise))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Ya existe");
        raise.setEffectiveFrom(LocalDate.of(2099, 2, 1));
        raise.setValue(new BigDecimal("101"));
        assertThatThrownBy(() -> parameterService.addVersion(raise))
                .isInstanceOf(BusinessException.class).hasMessageContaining("100");
        raise.setCode("OTRO");
        assertThatThrownBy(() -> parameterService.addVersion(raise))
                .isInstanceOf(BusinessException.class).hasMessageContaining("desconocido");
        assertThatThrownBy(() -> parameterService.effectiveAt(LocalDate.of(2025, 12, 31)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("IGSS_LABORAL_PCT");
    }

    @Test
    void scheduledJobAlsoPreparesAdministrationWithoutAUser() {
        assertThat(autoDraft.prepareDraftsFor(LocalDate.of(2026, 10, 28))).isEqualTo(1);
        PayrollRunResponse draft = runs.list().get(0);
        assertThat(draft.getPayGroup()).isEqualTo("ADMINISTRACION");
        assertThat(draft.getScheduledDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(draft.getPayDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(draft.getNotes()).contains("automáticamente");
    }

    @Test
    void employeeFileDerivesTheGroupValidatesValuesAndKeepsSalaryHistory() throws Exception {
        when(guard.canViewSalaries()).thenReturn(true);
        EmployeeResponse created = employeeService.createEmployee(EmployeeRequest.builder()
                .firstName("Nuevo").lastName("Planta").hireDate(LocalDate.of(2026, 3, 1))
                .salary(new BigDecimal("3500")).departmentId(production.getId()).paymentMethod("transferencia").build());
        assertThat(created.getPayGroup()).isEqualTo("PLANTA");
        assertThat(created.getEmploymentType()).isEqualTo("PLANILLA");
        assertThat(created.getPaymentMethod()).isEqualTo("TRANSFERENCIA BANCARIA");
        assertThat(salaryHistory.findByEmployeeIdOrderByEffectiveFromDescIdDesc(created.getId()))
                .singleElement().satisfies(h -> assertThat(h.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 3, 1)));

        EmployeeResponse moved = employeeService.updateEmployee(created.getId(),
                EmployeeRequest.builder().departmentId(office.getId()).salary(new BigDecimal("3800")).build());
        assertThat(moved.getPayGroup()).isEqualTo("ADMINISTRACION");
        assertThat(salaryHistory.findByEmployeeIdOrderByEffectiveFromDescIdDesc(created.getId())).hasSize(2);

        assertThatThrownBy(() -> employeeService.updateEmployee(created.getId(),
                EmployeeRequest.builder().employmentType("freelance").build()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("vinculación");
        assertThatThrownBy(() -> employeeService.updateEmployee(created.getId(),
                EmployeeRequest.builder().paymentMethod("tarjeta").build()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Forma de pago");
        assertThatThrownBy(() -> employeeService.updateEmployee(created.getId(),
                EmployeeRequest.builder().payGroup("kioscos").build()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Grupo de pago");
    }

    private EmployeeEntity employee(String firstName, String salary, String payGroup, String type, String payment,
                                    DepartmentEntity department) {
        return employees.save(EmployeeEntity.builder()
                .firstName(firstName).lastName("Prueba").salary(new BigDecimal(salary))
                .payGroup(payGroup).employmentType(type).paymentMethod(payment)
                .departmentId(department.getId()).status("active").build());
    }

    private EmployeeChargeEntity charge(EmployeeEntity employee, String amount, String settlement) {
        return charges.save(EmployeeChargeEntity.builder()
                .employeeId(employee.getId()).sourceType("PRUEBA").sourceId(System.nanoTime())
                .description("Compra de prueba").amount(new BigDecimal(amount)).balance(new BigDecimal(amount))
                .settlementMethod(settlement).chargedOn(LocalDate.of(2026, 10, 5)).build());
    }

    private static PayrollRunResponse.Line line(PayrollRunResponse run, EmployeeEntity employee) {
        return run.getLines().stream().filter(l -> l.getEmployeeId().equals(employee.getId())).findFirst().orElseThrow();
    }

    private static PayrollRunCreateRequest request(String group, LocalDate date) {
        PayrollRunCreateRequest request = new PayrollRunCreateRequest();
        request.setPayGroup(group);
        request.setScheduledDate(date);
        return request;
    }

    private static PayrollChequeAssignRequest cheques(String first) {
        PayrollChequeAssignRequest request = new PayrollChequeAssignRequest();
        request.setFirstChequeNumber(first);
        return request;
    }

    private static PayrollParameterEntity param(String code, String value, LocalDate from) {
        return PayrollParameterEntity.builder().code(code).value(new BigDecimal(value)).effectiveFrom(from).build();
    }

    private static BigDecimal credited(List<AccountingEntryEntity> posted, String account) {
        return posted.stream().filter(e -> account.equals(e.getAccountCode()))
                .map(AccountingEntryEntity::getCreditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String code(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }
}
