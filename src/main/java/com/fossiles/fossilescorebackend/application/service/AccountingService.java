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

    /**
     * Genera asientos contables al recibir materiales
     * CP-07-002: Debe: Inventario Materiales | Haber: Compras en Tránsito
     * Si hay variaciones, se generan asientos adicionales
     */
    public List<AccountingEntryResponse> generateMaterialReceiptEntries(Long materialReceiptId, Long purchaseOrderId, String orderCode, BigDecimal receivedTotal, BigDecimal orderedTotal, Long costCenterId) {
        List<AccountingEntryEntity> entries = new ArrayList<>();

        String inventoryAccount = getAccountCode(CONFIG_INVENTORY_ACCOUNT, DEFAULT_INVENTORY_ACCOUNT);
        String purchasesInTransitAccount = getAccountCode(CONFIG_PURCHASES_IN_TRANSIT_ACCOUNT, DEFAULT_PURCHASES_IN_TRANSIT_ACCOUNT);
        String accountsPayableAccount = getAccountCode(CONFIG_ACCOUNTS_PAYABLE_ACCOUNT, DEFAULT_ACCOUNTS_PAYABLE_ACCOUNT);
        String varianceAccount = getAccountCode(CONFIG_INVENTORY_VARIANCE_ACCOUNT, DEFAULT_INVENTORY_VARIANCE_ACCOUNT);

        // Asiento principal: Mover de Compras en Tránsito a Inventario
        // Debe: Inventario de Materiales
        AccountingEntryEntity inventoryEntry = AccountingEntryEntity.builder()
                .documentType("MATERIAL_RECEIPT")
                .documentId(materialReceiptId)
                .entryDate(LocalDateTime.now())
                .debitAmount(receivedTotal)
                .creditAmount(BigDecimal.ZERO)
                .accountCode(inventoryAccount)
                .accountName(getAccountName(inventoryAccount))
                .description("Recepción de materiales - Orden: " + orderCode)
                .costCenterId(costCenterId)
                .referenceNumber(orderCode)
                .build();
        entries.add(inventoryEntry);

        // Haber: Compras en Tránsito (por el monto recibido)
        AccountingEntryEntity transitEntry = AccountingEntryEntity.builder()
                .documentType("MATERIAL_RECEIPT")
                .documentId(materialReceiptId)
                .entryDate(LocalDateTime.now())
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(receivedTotal)
                .accountCode(purchasesInTransitAccount)
                .accountName(getAccountName(purchasesInTransitAccount))
                .description("Recepción de materiales - Orden: " + orderCode)
                .costCenterId(costCenterId)
                .referenceNumber(orderCode)
                .build();
        entries.add(transitEntry);

        // Si hay diferencia entre lo ordenado y lo recibido, generar asientos de variación
        BigDecimal variance = receivedTotal.subtract(orderedTotal);
        
        if (variance.compareTo(BigDecimal.ZERO) != 0) {
            // Si recibimos más de lo ordenado (exceso)
            if (variance.compareTo(BigDecimal.ZERO) > 0) {
                // Debe: Inventario (exceso)
                AccountingEntryEntity excessEntry = AccountingEntryEntity.builder()
                        .documentType("MATERIAL_RECEIPT")
                        .documentId(materialReceiptId)
                        .entryDate(LocalDateTime.now())
                        .debitAmount(variance)
                        .creditAmount(BigDecimal.ZERO)
                        .accountCode(inventoryAccount)
                        .accountName(getAccountName(inventoryAccount))
                        .description("Exceso de recepción - Orden: " + orderCode)
                        .costCenterId(costCenterId)
                        .referenceNumber(orderCode)
                        .build();
                entries.add(excessEntry);

                // Haber: Cuentas por Pagar (exceso)
                AccountingEntryEntity excessPayableEntry = AccountingEntryEntity.builder()
                        .documentType("MATERIAL_RECEIPT")
                        .documentId(materialReceiptId)
                        .entryDate(LocalDateTime.now())
                        .debitAmount(BigDecimal.ZERO)
                        .creditAmount(variance)
                        .accountCode(accountsPayableAccount)
                        .accountName(getAccountName(accountsPayableAccount))
                        .description("Exceso de recepción - Orden: " + orderCode)
                        .costCenterId(costCenterId)
                        .referenceNumber(orderCode)
                        .build();
                entries.add(excessPayableEntry);
            } else {
                // Si recibimos menos de lo ordenado (faltante)
                // Debe: Variación de Inventario
                AccountingEntryEntity varianceEntry = AccountingEntryEntity.builder()
                        .documentType("MATERIAL_RECEIPT")
                        .documentId(materialReceiptId)
                        .entryDate(LocalDateTime.now())
                        .debitAmount(variance.abs())
                        .creditAmount(BigDecimal.ZERO)
                        .accountCode(varianceAccount)
                        .accountName(getAccountName(varianceAccount))
                        .description("Faltante de recepción - Orden: " + orderCode)
                        .costCenterId(costCenterId)
                        .referenceNumber(orderCode)
                        .build();
                entries.add(varianceEntry);

                // Haber: Compras en Tránsito (faltante)
                AccountingEntryEntity shortfallEntry = AccountingEntryEntity.builder()
                        .documentType("MATERIAL_RECEIPT")
                        .documentId(materialReceiptId)
                        .entryDate(LocalDateTime.now())
                        .debitAmount(BigDecimal.ZERO)
                        .creditAmount(variance.abs())
                        .accountCode(purchasesInTransitAccount)
                        .accountName(getAccountName(purchasesInTransitAccount))
                        .description("Faltante de recepción - Orden: " + orderCode)
                        .costCenterId(costCenterId)
                        .referenceNumber(orderCode)
                        .build();
                entries.add(shortfallEntry);
            }
        }

        accountingEntryRepository.saveAll(entries);

        return entries.stream()
                .map(this::toAccountingEntryResponse)
                .collect(Collectors.toList());
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

        if (originalEntries.isEmpty()) {
            throw new BusinessException("No se encontraron asientos contables para la orden de compra: " + orderCode);
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
        return entries.stream()
                .map(this::toAccountingEntryResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene todos los asientos contables por tipo de documento
     */
    public List<AccountingEntryResponse> getEntriesByDocumentType(String documentType) {
        List<AccountingEntryEntity> entries = accountingEntryRepository.findByDocumentType(documentType);
        return entries.stream()
                .map(this::toAccountingEntryResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene todos los asientos contables por cuenta
     */
    public List<AccountingEntryResponse> getEntriesByAccount(String accountCode) {
        List<AccountingEntryEntity> entries = accountingEntryRepository.findByAccountCode(accountCode);
        return entries.stream()
                .map(this::toAccountingEntryResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene todos los asientos contables en un rango de fechas
     */
    public List<AccountingEntryResponse> getEntriesByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        List<AccountingEntryEntity> entries = accountingEntryRepository
                .findByEntryDateBetween(startDate, endDate);
        return entries.stream()
                .map(this::toAccountingEntryResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene todos los asientos contables
     */
    public List<AccountingEntryResponse> getAllEntries() {
        List<AccountingEntryEntity> entries = accountingEntryRepository.findAll();
        return entries.stream()
                .map(this::toAccountingEntryResponse)
                .sorted((a, b) -> b.getEntryDate().compareTo(a.getEntryDate())) // Más recientes primero
                .collect(Collectors.toList());
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
                .costCenterName(String.valueOf(costCenterName))
                .referenceNumber(entity.getReferenceNumber())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .build();
    }
}

