package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.*;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:receipt_integrity;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class MaterialReceiptIntegrityTest {
    @Autowired MaterialReceiptService service;
    @Autowired PurchaseService purchases;
    @Autowired InventoryService inventoryService;
    @Autowired PurchaseOrderRepository orders;
    @Autowired PurchaseOrderItemRepository orderItems;
    @Autowired MaterialReceiptRepository receipts;
    @Autowired MaterialReceiptItemRepository receiptItems;
    @Autowired MaterialRepository materials;
    @Autowired MaterialInventoryRepository stock;
    @Autowired MaterialFifoBatchRepository batches;
    @Autowired AccountingEntryRepository entries;
    @Autowired MaterialInventoryKardexRepository kardex;
    @Autowired SupplierRepository suppliers;
    @Autowired CostCenterRepository costCenters;
    @MockitoSpyBean AccountingService accounting;
    MaterialEntity material;
    PurchaseOrderEntity order;

    @BeforeEach void fixture() {
        fixture("10", "10");
    }

    private void fixture(String quantity, String price) {
        material = materials.save(MaterialEntity.builder().sku("R" + UUID.randomUUID().toString().substring(0, 8))
                .purchaseQuantity(BigDecimal.ONE).purchasePrice(bd(price)).quantity(BigDecimal.ZERO).build());
        stock.save(MaterialInventory.builder().materialId(material.getId()).materialName(material.getSku()).quantity(BigDecimal.ZERO).build());
        order = orders.save(PurchaseOrderEntity.builder().code("CP" + UUID.randomUUID().toString().substring(0, 8))
                .orderDate(LocalDate.now()).status("CREADA").total(bd(quantity).multiply(bd(price))).build());
        orderItems.save(PurchaseOrderItemEntity.builder().purchaseOrderId(order.getId()).materialId(material.getId())
                .quantity(bd(quantity)).unitPrice(bd(price)).build());
        accounting.generatePurchaseOrderEntries(order.getId(), order.getCode(), order.getTotal(), null);
    }

    @Test void partialReceiptsAccumulateAndClearOnlyTheirOrderedValue() throws Exception {
        var first = service.create(request("4", "12"));
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo("PARCIALMENTE_RECIBIDA");
        assertReceipt(first.getId(), "48", "-40", "-8");
        var second = service.create(request("6", "10"));
        assertReceipt(second.getId(), "60", "-60", "0");
        assertStock("10");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo("RECIBIDA");
    }

    @Test void excessIncreasesPayablesAndAnEditOfCompletedReceiptReopensOrder() throws Exception {
        var receipt = service.create(request("12", "12"));
        assertReceipt(receipt.getId(), "144", "-100", "-44");
        service.update(receipt.getId(), request("8", "11"));
        assertReceipt(receipt.getId(), "88", "-80", "-8");
        assertStock("8");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo("PARCIALMENTE_RECIBIDA");
        assertThat(entries.findByDocumentTypeAndDocumentId("MATERIAL_RECEIPT", receipt.getId())).hasSize(3);
        assertThat(entries.findByDocumentTypeAndDocumentId("MATERIAL_RECEIPT_ADJUSTMENT", receipt.getId())).hasSize(3);
    }

    @Test void cancellationPreservesDocumentAndReversesStockAndLedgerOnce() throws Exception {
        var first = service.create(request("4", "10"));
        var second = service.create(request("6", "10"));
        service.cancel(first.getId(), "Devolución al proveedor");
        assertReceipt(first.getId(), "0", "0", "0");
        assertReceipt(second.getId(), "60", "-60", "0");
        assertStock("6");
        long count = entries.count();
        service.cancel(first.getId(), "Reintento");
        assertThat(entries.count()).isEqualTo(count);
        assertStock("6");
        assertThat(receiptItems.findByMaterialReceiptId(first.getId())).hasSize(1);
        assertThat(receipts.findById(first.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
        service.cancel(second.getId(), "Devolución");
        assertStock("0");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo("CREADA");
    }

    @Test void changingPriceWithoutQuantityAdjustsLedgerAndItsBatch() throws Exception {
        var receipt = service.create(request("4", "10"));
        service.update(receipt.getId(), request("4", "12"));
        assertStock("4");
        assertReceipt(receipt.getId(), "48", "-40", "-8");
        assertThat(batches.findByReferenceTypeAndReferenceId("MATERIAL_RECEIPT", receipt.getId()).getFirst().getUnitCost()).isEqualByComparingTo("12");
        long count = entries.count();
        service.update(receipt.getId(), request("4", "12"));
        assertThat(entries.count()).isEqualTo(count);
    }

    @Test void partialRoundingClearsExactlyTheOrderedAmount() throws Exception {
        fixture("1", "0.01");
        var first = service.create(request("0.5", "0.01"));
        var second = service.create(request("0.5", "0.01"));
        var all = new ArrayList<>(entries.findByDocumentTypeAndDocumentId("MATERIAL_RECEIPT", first.getId()));
        all.addAll(entries.findByDocumentTypeAndDocumentId("MATERIAL_RECEIPT", second.getId()));
        assertThat(net(all, "1.1.3.02")).isEqualByComparingTo("-0.01");
    }

    @Test void repeatedReductionsCorrectOnlyThisReceiptsBatchAfterConsumption() throws Exception {
        var first = service.create(request("4", "10"));
        var second = service.create(request("2", "10"));
        inventoryService.decrementMaterialInventory(material.getId(), bd("2"), bd("10"), "PRODUCTION", order.getId(), order.getCode(), "Consumo");
        service.update(second.getId(), request("1", "10"));
        service.update(second.getId(), request("0", "10"));
        assertStock("2");
        assertThat(batches.findByReferenceTypeAndReferenceId("MATERIAL_RECEIPT", first.getId()).getFirst().getQuantityAvailable()).isEqualByComparingTo("2");
        assertThat(batches.findByReferenceTypeAndReferenceId("MATERIAL_RECEIPT", second.getId()).getFirst().getQuantityAvailable()).isEqualByComparingTo("0");
        assertReceipt(second.getId(), "0", "0", "0");
    }

    @Test void consumedReceiptCannotBeCancelledOrRecosted() throws Exception {
        var receipt = service.create(request("4", "10"));
        var batch = batches.findByReferenceTypeAndReferenceId("MATERIAL_RECEIPT", receipt.getId()).getFirst();
        batch.setQuantityAvailable(bd("2"));
        batches.save(batch);
        assertThatThrownBy(() -> service.cancel(receipt.getId(), "Devolución")).isInstanceOf(BusinessException.class).hasMessageContaining("consumido");
    }

    @Test void rejectsUnknownNegativeAndDuplicatedMaterialsBeforeSaving() {
        long count = receipts.count();
        var negative = request("-1", "10");
        assertThatThrownBy(() -> service.create(negative)).isInstanceOf(BusinessException.class);
        var duplicate = request("2", "10");
        duplicate.setItems(List.of(duplicate.getItems().getFirst(), duplicate.getItems().getFirst()));
        assertThatThrownBy(() -> service.create(duplicate)).isInstanceOf(BusinessException.class).hasMessageContaining("una sola vez");
        var foreign = request("2", "10");
        foreign.getItems().getFirst().setMaterialId(-9L);
        assertThatThrownBy(() -> service.create(foreign)).isInstanceOf(BusinessException.class);
        assertThat(receipts.count()).isEqualTo(count);
        assertStock("0");
    }

    @Test void repeatedOrderMaterialReceivesStockOnlyOnce() throws Exception {
        orderItems.save(PurchaseOrderItemEntity.builder().purchaseOrderId(order.getId()).materialId(material.getId())
                .quantity(bd("5")).unitPrice(bd("20")).build());
        var receipt = service.create(request("12", "15"));
        assertStock("12");
        assertReceipt(receipt.getId(), "180", "-140", "-40");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo("PARCIALMENTE_RECIBIDA");
        var response = purchases.getMaterialReceipts().stream().filter(r -> r.getId().equals(receipt.getId())).findFirst().orElseThrow();
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().getFirst().getQuantityOrdered()).isEqualByComparingTo("15");
        assertThat(response.getItems().getFirst().getQuantityReceived()).isEqualByComparingTo("12");
    }

    @Test void absentMaterialIsShownAsNotReceived() throws Exception {
        var other = materials.save(MaterialEntity.builder().sku("M" + UUID.randomUUID().toString().substring(0, 8)).build());
        orderItems.save(PurchaseOrderItemEntity.builder().purchaseOrderId(order.getId()).materialId(other.getId()).quantity(bd("5")).unitPrice(bd("20")).build());
        var receipt = service.create(request("4", "10"));
        var response = purchases.getMaterialReceipts().stream().filter(r -> r.getId().equals(receipt.getId())).findFirst().orElseThrow();
        var absent = response.getItems().stream().filter(i -> i.getMaterialId().equals(other.getId())).findFirst().orElseThrow();
        assertThat(absent.getQuantityReceived()).isEqualByComparingTo("0");
        assertThat(absent.getSubtotal()).isEqualByComparingTo("0");
    }

    @Test void accountingFiltersApplyTogetherAndSupportSingleDateBound() throws Exception {
        var receipt = service.create(request("4", "10"));
        var base = entries.findByDocumentTypeAndDocumentId("MATERIAL_RECEIPT", receipt.getId());
        base.forEach(e -> e.setEntryDate(LocalDateTime.of(2026, 10, 10, 12, 0)));
        entries.saveAll(base);
        assertThat(accounting.searchEntries("MATERIAL_RECEIPT", "1.1.3.01", LocalDateTime.of(2026, 10, 9, 0, 0), LocalDateTime.of(2026, 10, 11, 0, 0))).hasSize(1);
        assertThat(accounting.searchEntries("MATERIAL_RECEIPT", "1.1.3.01", LocalDateTime.of(2026, 10, 11, 0, 0), null)).isEmpty();
        assertThat(accounting.searchEntries("MATERIAL_RECEIPT", null, null, LocalDateTime.of(2026, 10, 9, 0, 0))).isEmpty();
        assertThatThrownBy(() -> accounting.searchEntries(null, null, LocalDateTime.of(2026, 10, 11, 0, 0), LocalDateTime.of(2026, 10, 9, 0, 0))).isInstanceOf(BusinessException.class);
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void accountingErrorRollsBackReceiptStockBatchesAndOrder() throws Exception {
        long receiptCount = receipts.count();
        long batchCount = batches.count();
        long entryCount = entries.count();
        doThrow(new BusinessException("Error contable verificado")).when(accounting)
                .reconcileMaterialReceiptEntries(anyLong(), anyString(), any(), any(), any());
        assertThatThrownBy(() -> service.create(request("4", "10"))).isInstanceOf(BusinessException.class);
        assertThat(receipts.count()).isEqualTo(receiptCount);
        assertThat(batches.count()).isEqualTo(batchCount);
        assertThat(entries.count()).isEqualTo(entryCount);
        assertStock("0");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo("CREADA");
    }

    @Test void receiptKeepsWeightedAverageCostAndRecordsExitsAsNegative() throws Exception {
        var current = stock.findByMaterialId(material.getId()).orElseThrow();
        current.setQuantity(bd("10"));
        stock.save(current);
        // Sin conversión de compra el costo no lo recalcula la entidad: es el promedio ponderado de las recepciones.
        material.setPurchaseQuantity(null);
        material.setUnitCost(bd("5"));
        materials.save(material);
        var receipt = service.create(request("10", "15"));
        assertThat(materials.findById(material.getId()).orElseThrow().getUnitCost()).isEqualByComparingTo("10");
        service.cancel(receipt.getId(), "Devolución al proveedor");
        assertThat(materials.findById(material.getId()).orElseThrow().getUnitCost()).isEqualByComparingTo("5");
        var movements = kardex.findByMaterialId(material.getId());
        assertThat(movements).filteredOn(m -> "ENTRY".equals(m.getMovementType())).allMatch(m -> m.getQuantity().signum() > 0);
        assertThat(movements).filteredOn(m -> "EXIT".equals(m.getMovementType())).isNotEmpty()
                .allMatch(m -> m.getQuantity().compareTo(bd("-10")) == 0);
    }

    @Test void unmarkedMaterialDoesNotCreateZeroLine() throws Exception {
        var other = materials.save(MaterialEntity.builder().sku("Z" + UUID.randomUUID().toString().substring(0, 8)).purchaseQuantity(BigDecimal.ONE).build());
        orderItems.save(PurchaseOrderItemEntity.builder().purchaseOrderId(order.getId()).materialId(other.getId()).quantity(bd("5")).unitPrice(bd("20")).build());
        var request = request("4", "10");
        request.setItems(List.of(request.getItems().getFirst(),
                MaterialReceiptItemRequest.builder().materialId(other.getId()).quantityReceived(BigDecimal.ZERO).build()));
        var receipt = service.create(request);
        assertThat(receiptItems.findByMaterialReceiptId(receipt.getId())).extracting(MaterialReceiptItemEntity::getMaterialId)
                .containsExactly(material.getId());
    }

    @Test void legacyReceiptWithoutDetailDoesNotBlockNewReceiptsButCannotBeEdited() throws Exception {
        var legacy = receipts.save(MaterialReceiptEntity.builder().purchaseOrderId(order.getId()).receiptDate(LocalDate.now()).build());
        var receipt = service.create(request("4", "10"));
        assertReceipt(receipt.getId(), "40", "-40", "0");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo("PARCIALMENTE_RECIBIDA");
        assertThatThrownBy(() -> service.update(legacy.getId(), request("4", "10"))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("histórica");
        assertStock("4");
    }

    @Test void purchaseWithoutLedgerCanBeCancelledAndNewPurchaseKeepsCostCenter() throws Exception {
        var supplier = suppliers.save(SupplierEntity.builder().name("Proveedor " + UUID.randomUUID().toString().substring(0, 6)).build());
        var legacy = orders.save(PurchaseOrderEntity.builder().code("CL" + UUID.randomUUID().toString().substring(0, 8))
                .supplierId(supplier.getId()).orderDate(LocalDate.now()).status("CREADA").total(bd("10")).build());
        assertThat(purchases.cancelPurchaseOrder(legacy.getId()).getStatus()).isEqualTo("CANCELADA");
        var center = costCenters.save(CostCenterEntity.builder().code("CC" + UUID.randomUUID().toString().substring(0, 6)).name("Producción").build());
        var created = purchases.createPurchaseOrder(PurchaseOrderRequest.builder().code("CN" + UUID.randomUUID().toString().substring(0, 8))
                .supplierId(supplier.getId()).costCenterId(center.getId())
                .items(List.of(PurchaseOrderItemRequest.builder().materialId(material.getId()).quantity(bd("2")).unitPrice(bd("10")).build())).build());
        assertThat(orders.findById(created.getId()).orElseThrow().getCostCenterId()).isEqualTo(center.getId());
        var receipt = service.create(MaterialReceiptRequest.builder().purchaseOrderId(created.getId()).items(List.of(MaterialReceiptItemRequest.builder()
                .materialId(material.getId()).quantityReceived(bd("2")).build())).build());
        assertThat(entries.findByDocumentTypeAndDocumentId("MATERIAL_RECEIPT", receipt.getId())).allMatch(e -> center.getId().equals(e.getCostCenterId()));
    }

    private MaterialReceiptRequest request(String quantity, String price) {
        return MaterialReceiptRequest.builder().purchaseOrderId(order.getId()).items(List.of(MaterialReceiptItemRequest.builder()
                .materialId(material.getId()).quantityReceived(bd(quantity)).unitPriceReceived(bd(price)).build())).build();
    }
    private void assertStock(String expected) { assertThat(stock.findByMaterialId(material.getId()).orElseThrow().getQuantity()).isEqualByComparingTo(expected); }
    private void assertReceipt(Long id, String inventory, String transit, String payable) {
        List<AccountingEntryEntity> ledger = new ArrayList<>(entries.findByDocumentTypeAndDocumentId("MATERIAL_RECEIPT", id));
        ledger.addAll(entries.findByDocumentTypeAndDocumentId("MATERIAL_RECEIPT_ADJUSTMENT", id));
        assertThat(net(ledger, "1.1.3.01")).isEqualByComparingTo(inventory);
        assertThat(net(ledger, "1.1.3.02")).isEqualByComparingTo(transit);
        assertThat(net(ledger, "2.1.1.01")).isEqualByComparingTo(payable);
        assertThat(ledger.stream().map(e -> e.getDebitAmount().subtract(e.getCreditAmount())).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");
    }
    private BigDecimal net(List<AccountingEntryEntity> ledger, String account) { return ledger.stream().filter(e -> account.equals(e.getAccountCode()))
            .map(e -> e.getDebitAmount().subtract(e.getCreditAmount())).reduce(BigDecimal.ZERO, BigDecimal::add); }
    private static BigDecimal bd(String value) { return new BigDecimal(value); }
}
