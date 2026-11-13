package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.EmployeeRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollChequeAssignRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollRunCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.EmployeeResponse;
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
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.AccountingEntryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.DepartmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeChargeApplicationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeChargeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InternalShipmentRequestRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayrollParameterRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
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

/** Casos límite de planilla: cambios de grupo, huella, cobros, cheques, permisos y envíos de compras. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:payroll_audit;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class PayrollAuditFixesIntegrationTest {
    static final LocalDate ADMIN_15_OCT = LocalDate.of(2026, 10, 15);
    static final LocalDate PLANT_20_OCT = LocalDate.of(2026, 10, 20);
    static final LocalDate ADMIN_31_OCT = LocalDate.of(2026, 10, 31);
    static final LocalDate PLANT_5_NOV = LocalDate.of(2026, 11, 5);

    @Autowired PayrollRunService runs;
    @Autowired EmployeeChargeService chargeService;
    @Autowired EmployeeService employeeService;
    @Autowired AccountingService accounting;
    @Autowired PurchaseReportService purchaseReports;
    @Autowired ProductDistributionService distribution;
    @Autowired EmployeeRepository employees;
    @Autowired DepartmentRepository departments;
    @Autowired EmployeeChargeRepository charges;
    @Autowired EmployeeChargeApplicationRepository applications;
    @Autowired PayrollParameterRepository parameters;
    @Autowired AccountingEntryRepository entries;
    @Autowired ProductRepository products;
    @Autowired ProductShipmentRepository shipments;
    @Autowired InternalShipmentRequestRepository requests;
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
        operator = employee("Ana", "4000", "PLANTA", "CHEQUE", production);
        clerk = employee("Carla", "6000", "ADMINISTRACION", "TRANSFERENCIA BANCARIA", office);
    }

    @Test
    void aGroupChangeMidPeriodNeitherPaysTwiceNorSkipsTheQuincena() throws Exception {
        EmployeeEntity elena = employee("Elena", "3000", "PLANTA", "CHEQUE", production);
        PayrollRunResponse office15 = runs.create(request("ADMINISTRACION", ADMIN_15_OCT));
        assertThat(office15.getLines()).extracting(PayrollRunResponse.Line::getEmployeeId).containsExactly(clerk.getId());
        runs.approve(office15.getId());

        clerk.setPayGroup("PLANTA");
        elena.setPayGroup("ADMINISTRACION");
        employees.saveAll(List.of(clerk, elena));

        PayrollRunResponse plant20 = runs.create(request("PLANTA", PLANT_20_OCT));
        assertThat(plant20.getLines()).extracting(PayrollRunResponse.Line::getEmployeeId)
                .containsExactlyInAnyOrder(operator.getId(), elena.getId());
        assertThat(plant20.getWarnings()).contains(
                "Ya cobraron este período en otra planilla por un cambio de grupo y no entran: 1",
                "Entran de otro grupo porque su planilla de este período ya se aprobó sin ellos: 1");

        PayrollRunResponse office31 = runs.create(request("ADMINISTRACION", ADMIN_31_OCT));
        assertThat(office31.getLines()).extracting(PayrollRunResponse.Line::getEmployeeId).containsExactly(elena.getId());
    }

    @Test
    void annullingAnotherDraftMakesThisOneStaleBecauseItCouldDeductMore() throws Exception {
        charge(operator, "300", "DESCUENTO");
        PayrollRunResponse october = runs.create(request("PLANTA", PLANT_20_OCT));
        PayrollRunResponse november = runs.create(request("PLANTA", PLANT_5_NOV));
        assertThat(line(november, operator).getOpiDiscount()).isEqualByComparingTo("0");
        assertThat(november.getStale()).isFalse();

        runs.annul(october.getId(), "Se rehace");
        assertThat(runs.get(november.getId()).getStale()).isTrue();
        assertThatThrownBy(() -> runs.approve(november.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("recalcule");
        assertThat(line(runs.recalculate(november.getId()), operator).getOpiDiscount()).isEqualByComparingTo("300.00");
    }

    @Test
    void everyCollectionOfAChargePostsItsOwnEntry() throws Exception {
        EmployeeChargeEntity purchase = charge(clerk, "5000", "DESCUENTO");
        PayrollRunResponse run = runs.create(request("ADMINISTRACION", ADMIN_31_OCT));
        BigDecimal deducted = line(run, clerk).getOpiDiscount();
        assertThat(deducted).isPositive().isLessThan(new BigDecimal("5000"));
        runs.approve(run.getId());

        chargeService.decide(purchase.getId(), "TRANSFERENCIA");
        chargeService.markPaid(purchase.getId());
        runs.annul(run.getId(), "Se pagó con el salario anterior");
        EmployeeChargeEntity reopened = charges.findById(purchase.getId()).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo("PARCIAL");
        assertThat(reopened.getBalance()).isEqualByComparingTo(deducted);
        chargeService.markPaid(purchase.getId());

        List<AccountingEntryEntity> collected = entries.findByDocumentTypeAndDocumentId("EMPLOYEE_CHARGE_PAYMENT", purchase.getId());
        assertThat(collected).hasSize(4);
        assertThat(debited(collected, "1.1.1.02")).isEqualByComparingTo("5000");
        assertThat(credited(collected, "1.1.2.05")).isEqualByComparingTo("5000");
    }

    @Test
    void chequeNumbersAreComparedByValueAndSavedInCapitals() throws Exception {
        PayrollRunResponse plant = runs.create(request("PLANTA", PLANT_20_OCT));
        runs.approve(plant.getId());
        assertThat(runs.assignCheques(plant.getId(), cheques(" 00000101 /gcf")).getLines())
                .extracting(PayrollRunResponse.Line::getChequeNumber).containsExactly("00000101/GCF");

        employee("Dora", "3500", "ADMINISTRACION", "CHEQUE", office);
        PayrollRunResponse admin = runs.approve(runs.create(request("ADMINISTRACION", ADMIN_31_OCT)).getId());
        assertThat(admin.getSuggestedFirstChequeNumber()).isEqualTo("00000102/GCF");
        assertThatThrownBy(() -> runs.assignCheques(admin.getId(), cheques("101/gcf")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("00000101/GCF (planilla #" + plant.getId() + ")");

        // Un cheque de una planilla anulada pudo imprimirse: la sugerencia sigue después de él.
        runs.annul(plant.getId(), "Cheques impresos con error");
        assertThat(runs.get(admin.getId()).getSuggestedFirstChequeNumber()).isEqualTo("00000102/GCF");
    }

    @Test
    void linesKeepTheStandardPaymentMethodAndAnUnknownOneBlocksApproval() throws Exception {
        operator.setPaymentMethod("cheque");
        employees.save(operator);
        EmployeeEntity bruno = employee("Bruno", "3000", "PLANTA", "Transferencia_Bancaria", production);
        EmployeeEntity tito = employee("Tito", "3000", "PLANTA", "tarjeta", production);

        PayrollRunResponse run = runs.create(request("PLANTA", PLANT_20_OCT));
        assertThat(line(run, operator).getPaymentMethod()).isEqualTo("CHEQUE");
        assertThat(line(run, bruno).getPaymentMethod()).isEqualTo("TRANSFERENCIA BANCARIA");
        assertThat(line(run, tito).getPaymentMethod()).isNull();
        assertThat(run.getWarnings()).contains("Sin forma de pago, que impide aprobar: 1",
                "Cobran por transferencia sin número de cuenta: 1");
        assertThatThrownBy(() -> runs.approve(run.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("sin forma de pago válida");
    }

    @Test
    void withoutPayrollAccessNobodyProbesSalariesOrRedirectsPayments() throws Exception {
        operator.setBankAccount("123-4");
        employees.save(operator);
        assertThatThrownBy(() -> employeeService.updateEmployee(operator.getId(),
                EmployeeRequest.builder().salary(new BigDecimal("4000")).build()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("salarios");
        assertThatThrownBy(() -> employeeService.updateEmployee(operator.getId(),
                EmployeeRequest.builder().bankAccount("999-1").build()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("la cuenta");
        assertThatThrownBy(() -> employeeService.updateEmployee(operator.getId(),
                EmployeeRequest.builder().paymentMethod("efectivo").build()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("la forma de pago");
        assertThatThrownBy(() -> employeeService.updateEmployee(operator.getId(),
                EmployeeRequest.builder().terminationDate(LocalDate.of(2026, 10, 31)).build()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("fecha de baja");

        // El formulario completo sin cambios en la ficha laboral pasa, y el departamento no mueve el grupo de pago.
        EmployeeResponse saved = employeeService.updateEmployee(operator.getId(), EmployeeRequest.builder()
                .phone("5555-0000").bankAccount("123-4").paymentMethod("cheque").employmentType("planilla")
                .payGroup("PLANTA").clearTerminationDate(true).departmentId(office.getId()).build());
        assertThat(saved.getPhone()).isEqualTo("5555-0000");
        assertThat(saved.getDepartmentId()).isEqualTo(office.getId());
        assertThat(saved.getPayGroup()).isEqualTo("PLANTA");
        assertThat(employees.findById(operator.getId()).orElseThrow().getSalary()).isEqualByComparingTo("4000");
    }

    @Test
    void cancellingAPurchaseShipmentCancelsItsUntouchedChargeAndBlocksADeductedOne() throws Exception {
        ProductEntity belt = products.save(ProductEntity.builder().code(code("C")).name("Cincho").salePrice(new BigDecimal("200")).build());
        ProductShipmentEntity first = shipment("CONFIRMED");
        EmployeeChargeEntity untouched = bought(first, belt);
        PayrollRunResponse draft = runs.create(request("PLANTA", PLANT_20_OCT));
        assertThat(line(draft, operator).getOpiDiscount()).isEqualByComparingTo("100.00");

        distribution.cancelShipment(first.getId());
        assertThat(charges.findById(untouched.getId()).orElseThrow().getStatus()).isEqualTo("ANULADO");
        assertThat(applications.findByChargeId(untouched.getId())).isEmpty();
        List<AccountingEntryEntity> reversed = entries.findByDocumentTypeAndDocumentId("EMPLOYEE_CHARGE_CANCELLATION", untouched.getId());
        assertThat(debited(reversed, "4.1.1.03")).isEqualByComparingTo("100");
        assertThat(credited(reversed, "1.1.2.05")).isEqualByComparingTo("100");
        assertThat(runs.get(draft.getId()).getStale()).isTrue();

        ProductShipmentEntity second = shipment("CONFIRMED");
        bought(second, belt);
        assertThat(line(runs.recalculate(draft.getId()), operator).getOpiDiscount()).isEqualByComparingTo("100.00");
        runs.approve(draft.getId());
        assertThatThrownBy(() -> distribution.cancelShipment(second.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya se descontó o se cobró");
        assertThat(shipments.findById(second.getId()).orElseThrow().getStatus()).isEqualTo("CONFIRMED");

        ProductShipmentEntity sent = shipment("SENT");
        bought(sent, belt);
        assertThatThrownBy(() -> distribution.revertSentShipment(sent.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("anúlelo y cree una");
    }

    @Test
    void aPlantWorkerWhoLeftMaySettleThePurchaseByCash() throws Exception {
        EmployeeChargeEntity purchase = charge(operator, "300", "DESCUENTO");
        assertThatThrownBy(() -> chargeService.decide(purchase.getId(), "EFECTIVO"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("planta");

        operator.setTerminationDate(LocalDate.of(2026, 10, 10));
        operator.setStatus("inactive");
        employees.save(operator);
        assertThat(chargeService.list(null, operator.getId())).singleElement()
                .satisfies(c -> assertThat(c.getEmployeeActive()).isFalse());
        chargeService.decide(purchase.getId(), "EFECTIVO");
        assertThat(chargeService.markPaid(purchase.getId()).getStatus()).isEqualTo("PAGADO");
        assertThat(debited(entries.findByDocumentTypeAndDocumentId("EMPLOYEE_CHARGE_PAYMENT", purchase.getId()), "1.1.1.01"))
                .isEqualByComparingTo("300");
    }

    @Test
    void employeesWithPayrollHistoryAreDeactivatedInsteadOfDeleted() throws Exception {
        runs.create(request("PLANTA", PLANT_20_OCT));
        assertThatThrownBy(() -> employeeService.deleteEmployee(operator.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("márquelo inactivo");
        charge(clerk, "80", null);
        assertThatThrownBy(() -> employeeService.deleteEmployee(clerk.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("márquelo inactivo");

        EmployeeEntity duplicate = employee("Duplicado", "3000", "ADMINISTRACION", "CHEQUE", office);
        employeeService.deleteEmployee(duplicate.getId());
        assertThat(employees.findById(duplicate.getId())).isEmpty();
    }

    @Test
    void payrollEntriesAreHiddenFromWhoeverCannotSeeSalaries() throws Exception {
        PayrollRunResponse run = runs.create(request("PLANTA", PLANT_20_OCT));
        runs.approve(run.getId());
        runs.assignCheques(run.getId(), cheques("300"));
        runs.pay(run.getId());

        assertThat(accounting.getEntriesByDocument("PAYROLL", run.getId())).isEmpty();
        assertThat(accounting.getEntriesByDocumentType("PAYROLL")).isEmpty();
        assertThat(accounting.searchEntries("PAYROLL", null, null, null)).isEmpty();
        assertThat(accounting.getAllEntries()).noneMatch(entry -> "PAYROLL".equals(entry.getDocumentType()));
        assertThat(purchaseReports.getAccountingEntriesReport(null, null, "PAYROLL").getTotalDebits()).isEqualByComparingTo("0");

        when(guard.canViewSalaries()).thenReturn(true);
        assertThat(accounting.getEntriesByDocument("PAYROLL", run.getId())).isNotEmpty();
        assertThat(purchaseReports.getAccountingEntriesReport(null, null, "PAYROLL").getTotalDebits()).isPositive();
    }

    private EmployeeEntity employee(String firstName, String salary, String payGroup, String payment, DepartmentEntity department) {
        return employees.save(EmployeeEntity.builder()
                .firstName(firstName).lastName("Prueba").salary(new BigDecimal(salary))
                .payGroup(payGroup).employmentType("PLANILLA").paymentMethod(payment)
                .departmentId(department.getId()).status("active").build());
    }

    private EmployeeChargeEntity charge(EmployeeEntity employee, String amount, String settlement) {
        return charges.save(EmployeeChargeEntity.builder()
                .employeeId(employee.getId()).sourceType("PRUEBA").sourceId(System.nanoTime())
                .description("Compra de prueba").amount(new BigDecimal(amount)).balance(new BigDecimal(amount))
                .settlementMethod(settlement).chargedOn(LocalDate.of(2026, 10, 5)).build());
    }

    private ProductShipmentEntity shipment(String status) {
        return shipments.save(ProductShipmentEntity.builder().shipmentNumber(code("ENVI-")).status(status).build());
    }

    /** Compra de colaborador de la operaria, ya despachada en el envío indicado; se fecha antes del día de pago. */
    private EmployeeChargeEntity bought(ProductShipmentEntity shipment, ProductEntity product) throws BusinessException {
        InternalShipmentRequestEntity request = InternalShipmentRequestEntity.builder()
                .employeeId(operator.getId()).requestType("PLANILLA").status("APROBADA").recipientName("Prueba")
                .productShipmentId(shipment.getId()).lines(new ArrayList<>()).build();
        request.getLines().add(InternalShipmentRequestLineEntity.builder()
                .request(request).lineOrder(1).productId(product.getId()).quantity(BigDecimal.ONE).build());
        EmployeeChargeEntity charge = chargeService.chargeInternalShipment(requests.save(request), shipment.getShipmentNumber());
        charge.setChargedOn(LocalDate.of(2026, 10, 5));
        return charges.save(charge);
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
