package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.EmployeeRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollChequeAssignRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollProfileRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollRunCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollProfileResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollRunResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.AccountingEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DepartmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeChargeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestLineEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollParameterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.AccountingEntryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.DepartmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeChargeApplicationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeChargeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeSalaryHistoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayrollParameterRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:payroll_run;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class PayrollRunIntegrationTest {
    static final LocalDate PLANT_20_OCT = LocalDate.of(2026, 10, 20);
    static final LocalDate ADMIN_31_OCT = LocalDate.of(2026, 10, 31);

    @Autowired PayrollRunService runs;
    @Autowired EmployeeChargeService chargeService;
    @Autowired EmployeeRepository employees;
    @Autowired DepartmentRepository departments;
    @Autowired EmployeeChargeRepository charges;
    @Autowired EmployeeChargeApplicationRepository applications;
    @Autowired PayrollParameterRepository parameters;
    @Autowired AccountingEntryRepository entries;
    @Autowired ProductRepository products;
    @Autowired PayrollAutoDraftJob autoDraft;
    @Autowired PayrollProfileService profiles;
    @Autowired EmployeeService employeeService;
    @Autowired EmployeeSalaryHistoryRepository salaryHistory;
    @MockitoBean PayrollAccessGuard guard;

    DepartmentEntity kiosks;
    EmployeeEntity operator;
    EmployeeEntity contractor;
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
        DepartmentEntity office = departments.save(DepartmentEntity.builder().code(code("A")).name("Administración").build());
        kiosks = departments.save(DepartmentEntity.builder().code(code("K")).name("Kioscos").build());
        operator = employee("Ana", "4000", "PLANTA", "PLANILLA", "CHEQUE", production);
        contractor = employee("Beto", "3000", "PLANTA", "SERVICIOS", "CHEQUE", production);
        clerk = employee("Carla", "6000", "ADMINISTRACION", "PLANILLA", "TRANSFERENCIA BANCARIA", office);
    }

    @Test
    void draftPaysThePlantGroupAndOnlyReservesPurchases() throws Exception {
        EmployeeChargeEntity purchase = charge(operator, "300", "DESCUENTO");

        PayrollRunResponse run = runs.create(request("PLANTA", PLANT_20_OCT));

        assertThat(run.getPayDate()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(run.getPeriodStart()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(run.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(run.getLines()).extracting(PayrollRunResponse.Line::getEmployeeId)
                .containsExactly(operator.getId(), contractor.getId());
        PayrollRunResponse.Line ana = run.getLines().get(0);
        assertThat(ana.getBaseSalary()).isEqualByComparingTo("2000.00");
        assertThat(ana.getBonification()).isEqualByComparingTo("125.00");
        assertThat(ana.getIgssEmployee()).isEqualByComparingTo("96.60");
        assertThat(ana.getOpiDiscount()).isEqualByComparingTo("300.00");
        assertThat(ana.getNetPay()).isEqualByComparingTo("1728.40");
        PayrollRunResponse.Line beto = run.getLines().get(1);
        assertThat(beto.getIgssEmployee()).isEqualByComparingTo("0");
        assertThat(beto.getNetPay()).isEqualByComparingTo("1500.00");
        assertThat(run.getTotalGross()).isEqualByComparingTo("3625.00");
        assertThat(run.getTotalDeductions()).isEqualByComparingTo("396.60");
        assertThat(run.getTotalNet()).isEqualByComparingTo("3228.40");
        assertThat(charges.findById(purchase.getId()).orElseThrow().getBalance()).isEqualByComparingTo("300");

        assertThatThrownBy(() -> runs.create(request("PLANTA", PLANT_20_OCT)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Ya existe");
        assertThatThrownBy(() -> runs.create(request("PLANTA", LocalDate.of(2026, 10, 15))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("5 y el 20");
    }

    @Test
    void approvalSettlesPurchasesAndPaymentPostsABalancedEntryOnce() throws Exception {
        EmployeeChargeEntity purchase = charge(operator, "300", "DESCUENTO");
        PayrollRunResponse run = runs.create(request("PLANTA", PLANT_20_OCT));

        runs.approve(run.getId());
        EmployeeChargeEntity settled = charges.findById(purchase.getId()).orElseThrow();
        assertThat(settled.getBalance()).isEqualByComparingTo("0");
        assertThat(settled.getStatus()).isEqualTo("PAGADO");

        assertThatThrownBy(() -> runs.pay(run.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Faltan 2");
        PayrollChequeAssignRequest cheques = new PayrollChequeAssignRequest();
        cheques.setFirstChequeNumber("99645508/GCF");
        PayrollRunResponse numbered = runs.assignCheques(run.getId(), cheques);
        assertThat(numbered.getLines()).extracting(PayrollRunResponse.Line::getChequeNumber)
                .containsExactly("99645508/GCF", "99645509/GCF");

        PayrollRunResponse paid = runs.pay(run.getId());
        assertThat(paid.getStatus()).isEqualTo("PAGADA");
        List<AccountingEntryEntity> posted = entries.findByDocumentTypeAndDocumentId("PAYROLL", run.getId());
        BigDecimal debit = posted.stream().map(AccountingEntryEntity::getDebitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = posted.stream().map(AccountingEntryEntity::getCreditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Sueldo 3,500 + bonificación 125 + cuota patronal 253.40.
        assertThat(debit).isEqualByComparingTo("3878.40").isEqualByComparingTo(credit);
        assertThat(credited(posted, "1.1.1.02")).isEqualByComparingTo("3228.40");
        assertThat(credited(posted, "1.1.2.05")).isEqualByComparingTo("300.00");
        assertThat(credited(posted, "2.1.3.01")).isEqualByComparingTo("350.00");

        assertThatThrownBy(() -> runs.annul(run.getId(), "error"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("pagada no se anula");
        assertThat(entries.findByDocumentTypeAndDocumentId("PAYROLL", run.getId())).hasSameSizeAs(posted);
    }

    @Test
    void annullingAnApprovedRunGivesTheBalanceBackAndFreesTheDate() throws Exception {
        EmployeeChargeEntity purchase = charge(operator, "5000", "DESCUENTO");
        PayrollRunResponse run = runs.create(request("PLANTA", PLANT_20_OCT));
        assertThat(run.getLines().get(0).getOpiDiscount()).isEqualByComparingTo("2028.40");
        assertThat(run.getLines().get(0).getNetPay()).isEqualByComparingTo("0");

        runs.approve(run.getId());
        assertThat(charges.findById(purchase.getId()).orElseThrow().getBalance()).isEqualByComparingTo("2971.60");
        assertThat(charges.findById(purchase.getId()).orElseThrow().getStatus()).isEqualTo("PARCIAL");

        runs.annul(run.getId(), "Se calculó con el salario anterior");
        EmployeeChargeEntity restored = charges.findById(purchase.getId()).orElseThrow();
        assertThat(restored.getBalance()).isEqualByComparingTo("5000");
        assertThat(restored.getStatus()).isEqualTo("PENDIENTE");
        assertThat(applications.findByChargeId(purchase.getId())).isEmpty();
        assertThat(runs.create(request("PLANTA", PLANT_20_OCT)).getStatus()).isEqualTo("BORRADOR");
    }

    @Test
    void administrationPurchasesWaitForAccountingAndKiosksCannotBuy() throws Exception {
        EmployeeChargeEntity undecided = charge(clerk, "400", null);
        PayrollRunResponse run = runs.create(request("ADMINISTRACION", ADMIN_31_OCT));
        assertThat(run.getPayDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(run.getLines()).singleElement().satisfies(line -> assertThat(line.getOpiDiscount()).isEqualByComparingTo("0"));

        chargeService.decide(undecided.getId(), "DESCUENTO");
        assertThat(runs.recalculate(run.getId()).getLines().get(0).getOpiDiscount()).isEqualByComparingTo("400.00");
        assertThatThrownBy(() -> chargeService.decide(undecided.getId(), "TRANSFERENCIA"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("borrador");

        EmployeeChargeEntity plantPurchase = charge(operator, "100", "DESCUENTO");
        assertThatThrownBy(() -> chargeService.decide(plantPurchase.getId(), "EFECTIVO"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("planta");

        EmployeeEntity cashier = employee("Dora", "3500", "ADMINISTRACION", "PLANILLA", "CHEQUE", kiosks);
        assertThatThrownBy(() -> chargeService.assertEmployeeMayBuy(cashier))
                .isInstanceOf(BusinessException.class).hasMessageContaining("kioscos");
    }

    @Test
    void approvedPurchaseIsChargedAtHalfTheCatalogPriceOnce() throws Exception {
        ProductEntity belt = products.save(ProductEntity.builder().code(code("C")).name("Cincho").salePrice(new BigDecimal("200")).build());
        InternalShipmentRequestEntity plantRequest = purchaseRequest(901L, operator, belt, "2");
        EmployeeChargeEntity plantCharge = chargeService.chargeInternalShipment(plantRequest, "ENVI-1");
        assertThat(plantCharge.getAmount()).isEqualByComparingTo("200.00");
        assertThat(plantCharge.getSettlementMethod()).isEqualTo("DESCUENTO");
        assertThat(chargeService.chargeInternalShipment(plantRequest, "ENVI-1").getId()).isEqualTo(plantCharge.getId());

        EmployeeChargeEntity officeCharge = chargeService.chargeInternalShipment(purchaseRequest(902L, clerk, belt, "1"), "ENVI-2");
        assertThat(officeCharge.getSettlementMethod()).isNull();

        ProductEntity unpriced = products.save(ProductEntity.builder().code(code("S")).name("Sin precio").build());
        assertThatThrownBy(() -> chargeService.assertChargeable(purchaseRequest(903L, operator, unpriced, "1")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("precio de venta");
    }

    private EmployeeEntity employee(String firstName, String salary, String payGroup, String type, String payment,
                                    DepartmentEntity department) {
        return employees.save(EmployeeEntity.builder()
                .firstName(firstName).lastName("Prueba").salary(new BigDecimal(salary))
                .payGroup(payGroup).employmentType(type).paymentMethod(payment)
                .departmentId(department.getId()).status("active").build());
    }

    @Test
    void approvalNeedsAFreshCalculationAndAPaymentMethodForEveryone() throws Exception {
        PayrollRunResponse run = runs.create(request("PLANTA", PLANT_20_OCT));
        assertThat(run.getStale()).isFalse();

        operator.setSalary(new BigDecimal("4200"));
        employees.save(operator);
        assertThat(runs.get(run.getId()).getStale()).isTrue();
        assertThatThrownBy(() -> runs.approve(run.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("recalcule");

        contractor.setPaymentMethod(null);
        employees.save(contractor);
        PayrollRunResponse recalculated = runs.recalculate(run.getId());
        assertThat(recalculated.getStale()).isFalse();
        assertThat(recalculated.getLines().get(0).getBaseSalary()).isEqualByComparingTo("2100.00");
        assertThat(recalculated.getWarnings()).contains("Sin forma de pago, que impide aprobar: 1");
        assertThatThrownBy(() -> runs.approve(run.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("sin forma de pago");
    }

    @Test
    void chequesFollowTheChequebookAndNeverRepeat() throws Exception {
        PayrollRunResponse plant = runs.create(request("PLANTA", PLANT_20_OCT));
        runs.approve(plant.getId());
        runs.assignCheques(plant.getId(), cheques("00000101/GCF"));

        employee("Dora", "3500", "ADMINISTRACION", "PLANILLA", "CHEQUE", kiosks);
        PayrollRunResponse admin = runs.create(request("ADMINISTRACION", ADMIN_31_OCT));
        PayrollRunResponse approved = runs.approve(admin.getId());
        assertThat(approved.getSuggestedFirstChequeNumber()).isEqualTo("00000103/GCF");
        assertThatThrownBy(() -> runs.assignCheques(admin.getId(), cheques("00000102/GCF")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("00000102/GCF (planilla #" + plant.getId() + ")");
        assertThat(runs.assignCheques(admin.getId(), cheques("00000103/GCF")).getLines())
                .filteredOn(line -> line.getChequeNumber() != null)
                .extracting(PayrollRunResponse.Line::getChequeNumber)
                .containsExactly("00000103/GCF");
    }

    @Test
    void payDateMovesEarlierWithinTenDaysAndADraftIsRecalculated() throws Exception {
        chargeOn(operator, "100", "DESCUENTO", LocalDate.of(2026, 10, 18));
        PayrollRunResponse run = runs.create(request("PLANTA", PLANT_20_OCT));
        assertThat(run.getLines().get(0).getOpiDiscount()).isEqualByComparingTo("100.00");

        PayrollRunResponse moved = runs.changePayDate(run.getId(), LocalDate.of(2026, 10, 16));
        assertThat(moved.getPayDate()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(moved.getLines().get(0).getOpiDiscount()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> runs.changePayDate(run.getId(), LocalDate.of(2026, 10, 21)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("posterior");
        assertThatThrownBy(() -> runs.changePayDate(run.getId(), LocalDate.of(2026, 10, 9)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("10 días");
    }

    @Test
    void scheduledJobPreparesTheNextDraftOnceAndNeverRecreatesAnAnnulledOne() throws Exception {
        assertThat(autoDraft.prepareDraftsFor(LocalDate.of(2026, 10, 17))).isEqualTo(1);
        PayrollRunResponse draft = runs.list().get(0);
        assertThat(draft.getPayGroup()).isEqualTo("PLANTA");
        assertThat(draft.getScheduledDate()).isEqualTo(PLANT_20_OCT);
        assertThat(draft.getStatus()).isEqualTo("BORRADOR");
        assertThat(autoDraft.prepareDraftsFor(LocalDate.of(2026, 10, 18))).isZero();

        runs.annul(draft.getId(), "Se prepara a mano");
        assertThat(autoDraft.prepareDraftsFor(LocalDate.of(2026, 10, 19))).isZero();
        assertThat(autoDraft.prepareDraftsFor(LocalDate.of(2026, 10, 28))).isEqualTo(1);
    }

    @Test
    void paymentEntrySplitsLaborCostByCostCenter() throws Exception {
        operator.setCostCenterId(7L);
        contractor.setCostCenterId(9L);
        employees.saveAll(List.of(operator, contractor));
        PayrollRunResponse run = runs.create(request("PLANTA", PLANT_20_OCT));
        runs.approve(run.getId());
        runs.assignCheques(run.getId(), cheques("500"));
        runs.pay(run.getId());

        assertThat(entries.findByDocumentTypeAndDocumentId("PAYROLL", run.getId()))
                .filteredOn(entry -> "5.1.2.01".equals(entry.getAccountCode()))
                .extracting(entry -> entry.getCostCenterId() + ":" + entry.getDebitAmount().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder("7:2000", "9:1500");
    }

    @Test
    void employeePurchaseStaysReceivableUntilItIsDeductedOrPaid() throws Exception {
        ProductEntity belt = products.save(ProductEntity.builder().code(code("C")).name("Cincho").salePrice(new BigDecimal("200")).build());
        EmployeeChargeEntity purchase = chargeService.chargeInternalShipment(purchaseRequest(911L, clerk, belt, "2"), "ENVI-9");
        List<AccountingEntryEntity> charged = entries.findByDocumentTypeAndDocumentId("EMPLOYEE_CHARGE", purchase.getId());
        assertThat(debited(charged, "1.1.2.05")).isEqualByComparingTo("200");
        assertThat(credited(charged, "4.1.1.03")).isEqualByComparingTo("200");

        chargeService.decide(purchase.getId(), "EFECTIVO");
        chargeService.markPaid(purchase.getId());
        List<AccountingEntryEntity> paid = entries.findByDocumentTypeAndDocumentId("EMPLOYEE_CHARGE_PAYMENT", purchase.getId());
        assertThat(debited(paid, "1.1.1.01")).isEqualByComparingTo("200");
        assertThat(credited(paid, "1.1.2.05")).isEqualByComparingTo("200");

        EmployeeChargeEntity mistake = chargeService.chargeInternalShipment(purchaseRequest(912L, operator, belt, "1"), "ENVI-10");
        chargeService.cancel(mistake.getId());
        List<AccountingEntryEntity> reversed = entries.findByDocumentTypeAndDocumentId("EMPLOYEE_CHARGE_CANCELLATION", mistake.getId());
        assertThat(debited(reversed, "4.1.1.03")).isEqualByComparingTo("100");
        assertThat(credited(reversed, "1.1.2.05")).isEqualByComparingTo("100");
    }

    @Test
    void profileUpdateReplacesThePayrollFileAndKeepsSalaryHistory() throws Exception {
        clerk.setBonification(new BigDecimal("125"));
        clerk.setTerminationDate(LocalDate.of(2026, 12, 31));
        employees.save(clerk);
        PayrollProfileRequest request = new PayrollProfileRequest();
        request.setEmploymentType("servicios");
        request.setPayGroup("ADMINISTRACION");
        request.setPaymentMethod("Transferencia  bancaria");
        request.setBankAccount(" 123-456 ");
        request.setSalary(new BigDecimal("6500"));

        PayrollProfileResponse updated = profiles.update(clerk.getId(), request);
        assertThat(updated.getEmploymentType()).isEqualTo("SERVICIOS");
        assertThat(updated.getPaymentMethod()).isEqualTo("TRANSFERENCIA BANCARIA");
        assertThat(updated.getBankAccount()).isEqualTo("123-456");
        assertThat(updated.getBonification()).isNull();
        assertThat(updated.getTerminationDate()).isNull();
        assertThat(salaryHistory.findByEmployeeIdOrderByEffectiveFromDescIdDesc(clerk.getId()))
                .first().satisfies(history -> assertThat(history.getSalary()).isEqualByComparingTo("6500"));

        request.setPayGroup("PLANTA");
        assertThat(profiles.update(clerk.getId(), request).getWarnings()).contains("Grupo distinto al de su departamento");
        request.setPayGroup("OTRO");
        assertThatThrownBy(() -> profiles.update(clerk.getId(), request))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Grupo de pago inválido");
        assertThat(profiles.list()).filteredOn(profile -> profile.getId().equals(operator.getId()))
                .singleElement().satisfies(profile -> assertThat(profile.getWarnings()).isEmpty());
    }

    @Test
    void salariesAreHiddenFromAnyoneWhoDoesNotWorkThePayroll() throws Exception {
        assertThat(employeeService.getEmployeeById(operator.getId()).getSalary()).isNull();
        assertThatThrownBy(() -> employeeService.updateEmployee(operator.getId(),
                EmployeeRequest.builder().salary(new BigDecimal("9000")).build()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Solo administración y Contabilidad");
        assertThat(employeeService.updateEmployee(operator.getId(), EmployeeRequest.builder().phone("5555-0000").build())
                .getPhone()).isEqualTo("5555-0000");
        assertThat(employees.findById(operator.getId()).orElseThrow().getSalary()).isEqualByComparingTo("4000");

        when(guard.canViewSalaries()).thenReturn(true);
        operator.setBonification(new BigDecimal("125"));
        operator.setTerminationDate(LocalDate.of(2026, 12, 31));
        employees.save(operator);
        var cleared = employeeService.updateEmployee(operator.getId(),
                EmployeeRequest.builder().clearBonification(true).clearTerminationDate(true).build());
        assertThat(cleared.getSalary()).isEqualByComparingTo("4000");
        assertThat(cleared.getBonification()).isNull();
        assertThat(cleared.getTerminationDate()).isNull();
    }

    private EmployeeChargeEntity charge(EmployeeEntity employee, String amount, String settlement) {
        return charges.save(EmployeeChargeEntity.builder()
                .employeeId(employee.getId()).sourceType("PRUEBA").sourceId(System.nanoTime())
                .description("Compra de prueba").amount(new BigDecimal(amount)).balance(new BigDecimal(amount))
                .settlementMethod(settlement).chargedOn(LocalDate.of(2026, 10, 5)).build());
    }

    private static InternalShipmentRequestEntity purchaseRequest(Long id, EmployeeEntity employee, ProductEntity product, String quantity) {
        InternalShipmentRequestEntity request = InternalShipmentRequestEntity.builder()
                .id(id).employeeId(employee.getId()).requestType("PLANILLA").status("APROBADA").recipientName("Prueba")
                .lines(new ArrayList<>()).build();
        request.getLines().add(InternalShipmentRequestLineEntity.builder()
                .request(request).lineOrder(1).productId(product.getId()).quantity(new BigDecimal(quantity)).build());
        return request;
    }

    private static PayrollRunCreateRequest request(String group, LocalDate date) {
        PayrollRunCreateRequest request = new PayrollRunCreateRequest();
        request.setPayGroup(group);
        request.setScheduledDate(date);
        return request;
    }

    private static PayrollParameterEntity param(String code, String value, LocalDate from) {
        return PayrollParameterEntity.builder().code(code).value(new BigDecimal(value)).effectiveFrom(from).build();
    }

    private EmployeeChargeEntity chargeOn(EmployeeEntity employee, String amount, String settlement, LocalDate chargedOn) {
        EmployeeChargeEntity charge = charge(employee, amount, settlement);
        charge.setChargedOn(chargedOn);
        return charges.save(charge);
    }

    private static PayrollChequeAssignRequest cheques(String first) {
        PayrollChequeAssignRequest request = new PayrollChequeAssignRequest();
        request.setFirstChequeNumber(first);
        return request;
    }

    private static BigDecimal debited(List<AccountingEntryEntity> posted, String account) {
        return posted.stream().filter(e -> account.equals(e.getAccountCode()))
                .map(AccountingEntryEntity::getDebitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal credited(List<AccountingEntryEntity> posted, String account) {
        return posted.stream().filter(e -> account.equals(e.getAccountCode()))
                .map(AccountingEntryEntity::getCreditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String code(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }
}
