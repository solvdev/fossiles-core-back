package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.AccountingEntryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.AccountingEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CostCenterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SystemConfigEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.AccountingEntryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CostCenterRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class AccountingService {

    private final AccountingEntryRepository accountingEntryRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final CostCenterRepository costCenterRepository;
    private final PayrollAccessGuard payrollAccessGuard;

    // Constantes para claves de configuración
    private static final String CONFIG_INVENTORY_ACCOUNT = "accounting.inventory.account";
    private static final String CONFIG_ACCOUNTS_PAYABLE_ACCOUNT = "accounting.accounts_payable.account";
    private static final String CONFIG_MATERIAL_COST_ACCOUNT = "accounting.material_cost.account";
    private static final String CONFIG_INVENTORY_VARIANCE_ACCOUNT = "accounting.inventory_variance.account";
    private static final String CONFIG_PURCHASES_IN_TRANSIT_ACCOUNT = "accounting.purchases_in_transit.account";

    // Valores por defecto de cuentas contables
    private static final String DEFAULT_INVENTORY_ACCOUNT = "1.1.3.01"; // Inventario de Materiales
    private static final String DEFAULT_ACCOUNTS_PAYABLE_ACCOUNT = "2.1.1.01"; // Cuentas por Pagar
    private static final String DEFAULT_MATERIAL_COST_ACCOUNT = "5.1.1.01"; // Costo de Materiales
    private static final String DEFAULT_INVENTORY_VARIANCE_ACCOUNT = "5.1.1.02"; // Variación de Inventario
    private static final String DEFAULT_PURCHASES_IN_TRANSIT_ACCOUNT = "1.1.3.02"; // Compras en Tránsito

    /**
     * Genera asientos contables al crear una orden de compra
     * CP-07-001: Debe: Compras en Tránsito | Haber: Cuentas por Pagar
     */
    public List<AccountingEntryResponse> generatePurchaseOrderEntries(Long purchaseOrderId, String orderCode, BigDecimal total, Long costCenterId) {
        List<AccountingEntryEntity> entries = new ArrayList<>();

        String purchasesInTransitAccount = getAccountCode(CONFIG_PURCHASES_IN_TRANSIT_ACCOUNT, DEFAULT_PURCHASES_IN_TRANSIT_ACCOUNT);
        String accountsPayableAccount = getAccountCode(CONFIG_ACCOUNTS_PAYABLE_ACCOUNT, DEFAULT_ACCOUNTS_PAYABLE_ACCOUNT);

        // Asiento 1: Debe - Compras en Tránsito (materiales ordenados pero no recibidos)
        AccountingEntryEntity debitEntry = AccountingEntryEntity.builder()
                .documentType("PURCHASE_ORDER")
                .documentId(purchaseOrderId)
                .entryDate(LocalDateTime.now())
                .debitAmount(total)
                .creditAmount(BigDecimal.ZERO)
                .accountCode(purchasesInTransitAccount)
                .accountName(getAccountName(purchasesInTransitAccount))
                .description("Compra de materiales - Orden: " + orderCode)
                .costCenterId(costCenterId)
                .referenceNumber(orderCode)
                .build();

        // Asiento 2: Haber - Cuentas por Pagar
        AccountingEntryEntity creditEntry = AccountingEntryEntity.builder()
                .documentType("PURCHASE_ORDER")
                .documentId(purchaseOrderId)
                .entryDate(LocalDateTime.now())
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(total)
                .accountCode(accountsPayableAccount)
                .accountName(getAccountName(accountsPayableAccount))
                .description("Cuentas por pagar - Orden: " + orderCode)
                .costCenterId(costCenterId)
                .referenceNumber(orderCode)
                .build();

        entries.add(debitEntry);
        entries.add(creditEntry);

        accountingEntryRepository.saveAll(entries);

        return entries.stream()
                .map(this::toAccountingEntryResponse)
                .collect(Collectors.toList());
    }

    /** El valor esperado corresponde a esta recepción, no al total de la orden. */
    public List<AccountingEntryResponse> generateMaterialReceiptEntries(Long receiptId, Long orderId, String orderCode,
            BigDecimal receivedTotal, BigDecimal expectedReceiptTotal, Long costCenterId) throws BusinessException {
        return reconcileMaterialReceiptEntries(receiptId, orderCode, receivedTotal, expectedReceiptTotal, costCenterId);
    }

    // Los ajustes se agregan al historial; una conciliación repetida no duplica el asiento.
    public List<AccountingEntryResponse> reconcileMaterialReceiptEntries(Long receiptId, String orderCode,
            BigDecimal receivedTotal, BigDecimal expectedReceiptTotal, Long costCenterId) throws BusinessException {
        BigDecimal actual = money(receivedTotal);
        BigDecimal expected = money(expectedReceiptTotal);
        if (actual.signum() < 0 || expected.signum() < 0) throw new BusinessException("Los importes de recepción no pueden ser negativos.");
        String inventory = getAccountCode(CONFIG_INVENTORY_ACCOUNT, DEFAULT_INVENTORY_ACCOUNT);
        String transit = getAccountCode(CONFIG_PURCHASES_IN_TRANSIT_ACCOUNT, DEFAULT_PURCHASES_IN_TRANSIT_ACCOUNT);
        String payable = getAccountCode(CONFIG_ACCOUNTS_PAYABLE_ACCOUNT, DEFAULT_ACCOUNTS_PAYABLE_ACCOUNT);
        if (new java.util.HashSet<>(List.of(inventory, transit, payable)).size() != 3) {
            throw new BusinessException("Configure cuentas diferentes para inventario, compras en tránsito y cuentas por pagar.");
        }
        java.util.Map<String, BigDecimal> desired = new java.util.LinkedHashMap<>();
        desired.put(inventory, actual);
        desired.put(transit, expected.negate());
        desired.put(payable, actual.subtract(expected).negate());
        List<AccountingEntryEntity> previous = new ArrayList<>(accountingEntryRepository
                .findByDocumentTypeAndDocumentId("MATERIAL_RECEIPT", receiptId));
        previous.addAll(accountingEntryRepository.findByDocumentTypeAndDocumentId("MATERIAL_RECEIPT_ADJUSTMENT", receiptId));
        java.util.Map<String, BigDecimal> current = new java.util.LinkedHashMap<>();
        for (var entry : previous) current.merge(entry.getAccountCode(),
                money(entry.getDebitAmount()).subtract(money(entry.getCreditAmount())), BigDecimal::add);
        if (current.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).signum() != 0) {
            throw new BusinessException("Los asientos existentes de la recepción no están balanceados; revise el documento antes de ajustarlo.");
        }
        java.util.Set<String> accounts = new java.util.LinkedHashSet<>(desired.keySet());
        accounts.addAll(current.keySet());
        List<AccountingEntryEntity> changes = new ArrayList<>();
        for (String account : accounts) {
            BigDecimal difference = desired.getOrDefault(account, BigDecimal.ZERO).subtract(current.getOrDefault(account, BigDecimal.ZERO));
            if (difference.signum() == 0) continue;
            changes.add(AccountingEntryEntity.builder()
                    .documentType(previous.isEmpty() ? "MATERIAL_RECEIPT" : "MATERIAL_RECEIPT_ADJUSTMENT")
                    .documentId(receiptId).entryDate(com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime.now())
                    .accountCode(account).accountName(getAccountName(account))
                    .debitAmount(difference.signum() > 0 ? difference : BigDecimal.ZERO)
                    .creditAmount(difference.signum() < 0 ? difference.abs() : BigDecimal.ZERO)
                    .description((previous.isEmpty() ? "Recepción" : "Ajuste de recepción") + " #" + receiptId + " - Orden: " + orderCode)
                    .costCenterId(costCenterId).referenceNumber(orderCode).build());
        }
        if (!changes.isEmpty()) accountingEntryRepository.saveAll(changes);
        return changes.stream().map(this::toAccountingEntryResponse).toList();
    }

    private static BigDecimal money(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public List<AccountingEntryResponse> searchEntries(String documentType, String accountCode,
            LocalDateTime startDate, LocalDateTime endDate) throws BusinessException {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessException("La fecha inicial no puede ser posterior a la fecha final.");
        }
        org.springframework.data.jpa.domain.Specification<AccountingEntryEntity> filters = (root, query, builder) -> {
            List<jakarta.persistence.criteria.Predicate> conditions = new ArrayList<>();
            if (documentType != null && !documentType.isBlank()) conditions.add(builder.equal(root.get("documentType"), documentType.trim()));
            if (accountCode != null && !accountCode.isBlank()) conditions.add(builder.equal(root.get("accountCode"), accountCode.trim()));
            if (startDate != null) conditions.add(builder.greaterThanOrEqualTo(root.get("entryDate"), startDate));
            if (endDate != null) conditions.add(builder.lessThanOrEqualTo(root.get("entryDate"), endDate));
            return builder.and(conditions.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var sort = org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "entryDate", "id");
        boolean filtered = (documentType != null && !documentType.isBlank()) || (accountCode != null && !accountCode.isBlank())
                || startDate != null || endDate != null;
        var entries = filtered ? accountingEntryRepository.findAll(filters, sort)
                : accountingEntryRepository.findAll(filters, org.springframework.data.domain.PageRequest.of(0, 1000, sort)).getContent();
        return visible(entries).stream().map(this::toAccountingEntryResponse).toList();
    }

    /**
     * La partida de planilla desglosa sueldos por centro de costo; quien no puede ver salarios no la recibe.
     */
    List<AccountingEntryEntity> visible(List<AccountingEntryEntity> entries) {
        boolean hasPayroll = entries.stream().anyMatch(entry -> PAYROLL_DOCUMENT_TYPE.equals(entry.getDocumentType()));
        if (!hasPayroll || payrollAccessGuard.canViewSalaries()) {
            return entries;
        }
        return entries.stream().filter(entry -> !PAYROLL_DOCUMENT_TYPE.equals(entry.getDocumentType())).toList();
    }


    /**
     * Genera asientos contables al cancelar una orden de compra
     * CP-07-003: Revierte los asientos originales
     * Debe: Cuentas por Pagar | Haber: Compras en Tránsito
     */
    public List<AccountingEntryResponse> generatePurchaseOrderCancellationEntries(Long purchaseOrderId, String orderCode, BigDecimal total, Long costCenterId) throws BusinessException {
        // Buscar asientos originales
        List<AccountingEntryEntity> originalEntries = accountingEntryRepository
                .findByDocumentTypeAndDocumentId("PURCHASE_ORDER", purchaseOrderId);

        // Una compra anterior a la contabilidad automática no tiene nada que revertir; su anulación no debe bloquearse.
        if (originalEntries.isEmpty()) {
            return List.of();
        }

        List<AccountingEntryEntity> reversalEntries = new ArrayList<>();

        // Crear asientos de reversión (contrapartida)
        // CP-07-003: Debe: Cuentas por Pagar | Haber: Compras en Tránsito
        for (AccountingEntryEntity original : originalEntries) {
            AccountingEntryEntity reversal = AccountingEntryEntity.builder()
                    .documentType("PURCHASE_ORDER_CANCELLATION")
                    .documentId(purchaseOrderId)
                    .entryDate(LocalDateTime.now())
                    .debitAmount(original.getCreditAmount()) // Invertir: lo que era Haber ahora es Debe
                    .creditAmount(original.getDebitAmount()) // Invertir: lo que era Debe ahora es Haber
                    .accountCode(original.getAccountCode())
                    .accountName(original.getAccountName())
                    .description("Cancelación de orden: " + orderCode + " - " + original.getDescription())
                    .costCenterId(original.getCostCenterId() != null ? original.getCostCenterId() : costCenterId)
                    .referenceNumber(orderCode)
                    .build();
            reversalEntries.add(reversal);
        }

        accountingEntryRepository.saveAll(reversalEntries);

        return reversalEntries.stream()
                .map(this::toAccountingEntryResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene todos los asientos contables de un documento
     */
    public List<AccountingEntryResponse> getEntriesByDocument(String documentType, Long documentId) {
        List<AccountingEntryEntity> entries = accountingEntryRepository
                .findByDocumentTypeAndDocumentId(documentType, documentId);
        return visible(entries).stream()
                .map(this::toAccountingEntryResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene todos los asientos contables por tipo de documento
     */
    public List<AccountingEntryResponse> getEntriesByDocumentType(String documentType) {
        List<AccountingEntryEntity> entries = accountingEntryRepository.findByDocumentType(documentType);
        return visible(entries).stream()
                .map(this::toAccountingEntryResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene todos los asientos contables por cuenta
     */
    public List<AccountingEntryResponse> getEntriesByAccount(String accountCode) {
        List<AccountingEntryEntity> entries = accountingEntryRepository.findByAccountCode(accountCode);
        return visible(entries).stream()
                .map(this::toAccountingEntryResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene todos los asientos contables en un rango de fechas
     */
    public List<AccountingEntryResponse> getEntriesByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        List<AccountingEntryEntity> entries = accountingEntryRepository
                .findByEntryDateBetween(startDate, endDate);
        return visible(entries).stream()
                .map(this::toAccountingEntryResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene todos los asientos contables
     */
    public List<AccountingEntryResponse> getAllEntries() {
        List<AccountingEntryEntity> entries = accountingEntryRepository.findAll();
        return visible(entries).stream()
                .map(this::toAccountingEntryResponse)
                .sorted((a, b) -> b.getEntryDate().compareTo(a.getEntryDate())) // Más recientes primero
                .collect(Collectors.toList());
    }

    /** Cuentas de planilla, configurables en {@code accounting.payroll.*}. */
    public enum PayrollAccount {
        SALARIES_PLANT("accounting.payroll.salaries_plant.account", "5.1.2.01", "Mano de obra directa"),
        SALARIES_ADMIN("accounting.payroll.salaries_admin.account", "6.1.1.01", "Sueldos de administración"),
        BONIFICATION("accounting.payroll.bonification.account", "6.1.1.02", "Bonificación incentivo"),
        EMPLOYER_CONTRIBUTIONS("accounting.payroll.employer_contributions.account", "6.1.1.03", "Cuota patronal IGSS, IRTRA e INTECAP"),
        IGSS_PAYABLE("accounting.payroll.igss_payable.account", "2.1.3.01", "IGSS por pagar"),
        ISR_PAYABLE("accounting.payroll.isr_payable.account", "2.1.3.02", "ISR retenido por pagar"),
        OTHER_DEDUCTIONS_PAYABLE("accounting.payroll.other_deductions.account", "2.1.3.03", "Otras deducciones por pagar"),
        EMPLOYEE_RECEIVABLE("accounting.payroll.employee_receivable.account", "1.1.2.05", "Cuentas por cobrar a colaboradores"),
        EMPLOYEE_SALES("accounting.payroll.employee_sales.account", "4.1.1.03", "Ventas a colaboradores"),
        BANK("accounting.payroll.bank.account", "1.1.1.02", "Bancos"),
        CASH("accounting.payroll.cash.account", "1.1.1.01", "Caja");

        public final String configKey;
        public final String defaultCode;
        public final String label;

        PayrollAccount(String configKey, String defaultCode, String label) {
            this.configKey = configKey;
            this.defaultCode = defaultCode;
            this.label = label;
        }
    }

    public static final String PAYROLL_DOCUMENT_TYPE = "PAYROLL";
    public static final String EMPLOYEE_CHARGE_DOCUMENT_TYPE = "EMPLOYEE_CHARGE";
    public static final String EMPLOYEE_CHARGE_PAYMENT_DOCUMENT_TYPE = "EMPLOYEE_CHARGE_PAYMENT";
    public static final String EMPLOYEE_CHARGE_CANCELLATION_DOCUMENT_TYPE = "EMPLOYEE_CHARGE_CANCELLATION";

    /** Una línea de partida: cuenta y centro de costo. El monto va en positivo al debe y en negativo al haber. */
    private record PayrollPosting(String code, Long costCenterId) {}

    /**
     * Partida de una planilla pagada: gasto de sueldo, bonificación y cuota patronal por centro de costo, contra IGSS e
     * ISR por pagar, el descuento de compras de colaborador y el banco o la caja por el líquido.
     * Una segunda llamada no duplica la partida.
     */
    public List<AccountingEntryResponse> postPayrollRun(
            com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollRunEntity run,
            List<com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollRunLineEntity> lines)
            throws BusinessException {
        List<AccountingEntryEntity> existing = accountingEntryRepository.findByDocumentTypeAndDocumentId(PAYROLL_DOCUMENT_TYPE, run.getId());
        if (!existing.isEmpty()) {
            return existing.stream().map(this::toAccountingEntryResponse).toList();
        }
        java.util.Map<PayrollPosting, BigDecimal> signed = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> names = new java.util.HashMap<>();
        PayrollAccount salaries = "PLANTA".equals(run.getPayGroup()) ? PayrollAccount.SALARIES_PLANT : PayrollAccount.SALARIES_ADMIN;
        for (var line : lines) {
            BigDecimal employer = money(line.getIgssEmployer()).add(money(line.getIrtra())).add(money(line.getIntecap()));
            Long costCenter = line.getCostCenterId();
            addPayrollAmount(signed, names, salaries, costCenter, money(line.getBaseSalary()));
            addPayrollAmount(signed, names, PayrollAccount.BONIFICATION, costCenter, money(line.getBonification()));
            addPayrollAmount(signed, names, PayrollAccount.EMPLOYER_CONTRIBUTIONS, costCenter, employer);
            addPayrollAmount(signed, names, PayrollAccount.IGSS_PAYABLE, null, money(line.getIgssEmployee()).add(employer).negate());
            addPayrollAmount(signed, names, PayrollAccount.ISR_PAYABLE, null, money(line.getIsr()).negate());
            addPayrollAmount(signed, names, PayrollAccount.OTHER_DEDUCTIONS_PAYABLE, null, money(line.getOtherDeductions()).negate());
            addPayrollAmount(signed, names, PayrollAccount.EMPLOYEE_RECEIVABLE, null, money(line.getOpiDiscount()).negate());
            PayrollAccount funds = "EFECTIVO".equals(line.getPaymentMethod()) ? PayrollAccount.CASH : PayrollAccount.BANK;
            addPayrollAmount(signed, names, funds, null, money(line.getNetPay()).negate());
        }
        if (signed.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).signum() != 0) {
            throw new BusinessException("La partida de la planilla no cuadra; revise los montos antes de registrar el pago.");
        }
        java.time.format.DateTimeFormatter format = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        String description = "Planilla " + run.getPayGroup() + " del " + run.getPeriodStart().format(format)
                + " al " + run.getPeriodEnd().format(format);
        List<AccountingEntryEntity> entries = new ArrayList<>();
        for (var entry : signed.entrySet()) {
            BigDecimal amount = entry.getValue();
            if (amount.signum() == 0) continue;
            entries.add(AccountingEntryEntity.builder()
                    .documentType(PAYROLL_DOCUMENT_TYPE)
                    .documentId(run.getId())
                    .entryDate(run.getPayDate().atStartOfDay())
                    .accountCode(entry.getKey().code())
                    .accountName(names.get(entry.getKey().code()))
                    .debitAmount(amount.signum() > 0 ? amount : BigDecimal.ZERO)
                    .creditAmount(amount.signum() < 0 ? amount.abs() : BigDecimal.ZERO)
                    .costCenterId(entry.getKey().costCenterId())
                    .description(description)
                    .referenceNumber("PLANILLA-" + run.getId())
                    .build());
        }
        accountingEntryRepository.saveAll(entries);
        return entries.stream().map(this::toAccountingEntryResponse).toList();
    }

    // Dos conceptos configurados con el mismo código se agrupan en una sola línea por centro de costo.
    private void addPayrollAmount(java.util.Map<PayrollPosting, BigDecimal> signed, java.util.Map<String, String> names,
                                  PayrollAccount account, Long costCenterId, BigDecimal amount) {
        if (amount.signum() == 0) {
            return;
        }
        String code = getAccountCode(account.configKey, account.defaultCode);
        signed.merge(new PayrollPosting(code, costCenterId), amount, BigDecimal::add);
        names.putIfAbsent(code, account.label);
    }

    /**
     * Compra de colaborador aprobada: queda por cobrar hasta que la planilla la descuente o el colaborador la pague.
     * La cuenta por cobrar va sin centro de costo, igual que el descuento y el cobro que la saldan; la venta lleva el
     * centro del colaborador. No duplica la partida si ya existe.
     */
    public void postEmployeeCharge(Long chargeId, BigDecimal amount, String reference, Long costCenterId) {
        postEmployeeChargePair(EMPLOYEE_CHARGE_DOCUMENT_TYPE, chargeId, PayrollAccount.EMPLOYEE_RECEIVABLE, null,
                PayrollAccount.EMPLOYEE_SALES, costCenterId, amount, "Compra de colaborador " + reference, reference, true);
    }

    /**
     * Cobro por transferencia o en efectivo de lo que no se descontó en planilla. Un cargo puede cobrarse más de una
     * vez (si se anula la planilla que descontó una parte), así que cada cobro lleva su partida.
     */
    public void postEmployeeChargePayment(Long chargeId, BigDecimal amount, boolean cash, String reference) {
        postEmployeeChargePair(EMPLOYEE_CHARGE_PAYMENT_DOCUMENT_TYPE, chargeId, cash ? PayrollAccount.CASH : PayrollAccount.BANK, null,
                PayrollAccount.EMPLOYEE_RECEIVABLE, null, amount, "Pago de compra de colaborador " + reference, reference, false);
    }

    /** Anulación de un cargo registrado por error: revierte la partida original, si la hubo. */
    public void postEmployeeChargeCancellation(Long chargeId, String reference) {
        if (!accountingEntryRepository.findByDocumentTypeAndDocumentId(EMPLOYEE_CHARGE_CANCELLATION_DOCUMENT_TYPE, chargeId).isEmpty()) {
            return;
        }
        List<AccountingEntryEntity> reversal = accountingEntryRepository
                .findByDocumentTypeAndDocumentId(EMPLOYEE_CHARGE_DOCUMENT_TYPE, chargeId).stream()
                .map(original -> AccountingEntryEntity.builder()
                        .documentType(EMPLOYEE_CHARGE_CANCELLATION_DOCUMENT_TYPE)
                        .documentId(chargeId)
                        .entryDate(com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime.now())
                        .accountCode(original.getAccountCode())
                        .accountName(original.getAccountName())
                        .debitAmount(original.getCreditAmount())
                        .creditAmount(original.getDebitAmount())
                        .costCenterId(original.getCostCenterId())
                        .description("Anulación de compra de colaborador " + reference)
                        .referenceNumber(reference)
                        .build())
                .toList();
        if (!reversal.isEmpty()) {
            accountingEntryRepository.saveAll(reversal);
        }
    }

    private void postEmployeeChargePair(String documentType, Long chargeId, PayrollAccount debit, Long debitCostCenter,
                                        PayrollAccount credit, Long creditCostCenter, BigDecimal amount,
                                        String description, String reference, boolean onlyOnce) {
        BigDecimal value = money(amount);
        if (value.signum() <= 0
                || (onlyOnce && !accountingEntryRepository.findByDocumentTypeAndDocumentId(documentType, chargeId).isEmpty())) {
            return;
        }
        java.time.LocalDateTime now = com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime.now();
        accountingEntryRepository.saveAll(List.of(
                AccountingEntryEntity.builder()
                        .documentType(documentType).documentId(chargeId).entryDate(now)
                        .accountCode(getAccountCode(debit.configKey, debit.defaultCode)).accountName(debit.label)
                        .debitAmount(value).creditAmount(BigDecimal.ZERO).costCenterId(debitCostCenter)
                        .description(description).referenceNumber(reference).build(),
                AccountingEntryEntity.builder()
                        .documentType(documentType).documentId(chargeId).entryDate(now)
                        .accountCode(getAccountCode(credit.configKey, credit.defaultCode)).accountName(credit.label)
                        .debitAmount(BigDecimal.ZERO).creditAmount(value).costCenterId(creditCostCenter)
                        .description(description).referenceNumber(reference).build()));
    }

    /**
     * Obtiene el código de cuenta desde configuración o usa el valor por defecto
     */
    private String getAccountCode(String configKey, String defaultValue) {
        return systemConfigRepository.findByConfigKey(configKey)
                .map(SystemConfigEntity::getConfigValue)
                .orElse(defaultValue);
    }

    /**
     * Obtiene el nombre de la cuenta (simplificado, en producción debería venir de un catálogo)
     */
    private String getAccountName(String accountCode) {
        // En producción, esto debería consultar un catálogo de cuentas contables
        // Por ahora, retornamos un nombre basado en el código
        if (accountCode.startsWith("1.1.3.01") || accountCode.equals("1.1.3.01")) {
            return "Inventario de Materiales";
        } else if (accountCode.startsWith("1.1.3.02") || accountCode.equals("1.1.3.02")) {
            return "Compras en Tránsito";
        } else if (accountCode.startsWith("2.1.1")) {
            return "Cuentas por Pagar";
        } else if (accountCode.startsWith("5.1.1.01") || accountCode.equals("5.1.1.01")) {
            return "Costo de Materiales";
        } else if (accountCode.startsWith("5.1.1.02") || accountCode.equals("5.1.1.02")) {
            return "Variación de Inventario";
        }
        return "Cuenta " + accountCode;
    }

    /**
     * Convierte entidad a DTO
     */
    private AccountingEntryResponse toAccountingEntryResponse(AccountingEntryEntity entity) {
        AtomicReference<String> costCenterName = new AtomicReference<>();
        if (entity.getCostCenterId() != null) {
            costCenterRepository.findById(entity.getCostCenterId())
                    .map(CostCenterEntity::getName)
                    .ifPresent(costCenterName::set);
        }

        return AccountingEntryResponse.builder()
                .id(entity.getId())
                .documentType(entity.getDocumentType())
                .documentId(entity.getDocumentId())
                .entryDate(entity.getEntryDate())
                .debitAmount(entity.getDebitAmount())
                .creditAmount(entity.getCreditAmount())
                .accountCode(entity.getAccountCode())
                .accountName(entity.getAccountName())
                .description(entity.getDescription())
                .costCenterId(entity.getCostCenterId())
                .costCenterName(costCenterName.get())
                .referenceNumber(entity.getReferenceNumber())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .build();
    }
}

