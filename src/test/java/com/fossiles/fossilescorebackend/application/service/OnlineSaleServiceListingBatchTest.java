package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.OnlineSaleResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaxInvoiceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Los listados cargan items y facturas por lote, no dos consultas por cada venta. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OnlineSaleServiceListingBatchTest {

    @Mock private OnlineSaleRepository saleRepository;
    @Mock private OnlineSaleItemRepository itemRepository;
    @Mock private TaxInvoiceRepository taxInvoiceRepository;

    @InjectMocks
    private OnlineSaleService service;

    private OnlineSaleEntity sale(Long id, Long invoiceId) {
        return OnlineSaleEntity.builder()
                .id(id).saleNumber(String.valueOf(id)).customerName("CLIENTE " + id)
                .saleDate(LocalDate.of(2026, 10, 13)).paymentMethod("EFECTIVO").status("PENDIENTE")
                .invoiceId(invoiceId).totalAmount(new BigDecimal("100.00")).build();
    }

    private OnlineSaleItemEntity item(Long id, Long saleId, String productCode) {
        return OnlineSaleItemEntity.builder()
                .id(id).onlineSaleId(saleId).productId(7L).productCode(productCode).quantity(1)
                .unitPrice(new BigDecimal("100.00")).subtotal(new BigDecimal("100.00")).build();
    }

    @Test
    void getByDateRange_cargaItemsYFacturasEnUnaConsultaPorLote() {
        when(saleRepository.findBySaleDateBetweenOrderBySaleDateDesc(any(), any()))
                .thenReturn(List.of(sale(1L, 500L), sale(2L, null), sale(3L, null)));
        when(itemRepository.findByOnlineSaleIdInOrderByIdAsc(any())).thenReturn(List.of(
                item(10L, 1L, "A"), item(11L, 1L, "B"), item(12L, 2L, "C")));
        TaxInvoiceEntity invoiceById = TaxInvoiceEntity.builder().id(500L).status("CERTIFIED").felUuid("U-500").build();
        TaxInvoiceEntity invoiceBySource = TaxInvoiceEntity.builder()
                .id(600L).sourceType("ONLINE_SALE").sourceId(2L).status("PENDING").build();
        when(taxInvoiceRepository.findAllById(any())).thenReturn(List.of(invoiceById));
        when(taxInvoiceRepository.findBySourceTypeAndSourceIdInOrderByIdDesc(eq("ONLINE_SALE"), any()))
                .thenReturn(List.of(invoiceBySource));

        List<OnlineSaleResponse> result = service.getByDateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 13));

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getItems()).extracting(OnlineSaleResponse.SaleItemResponse::getProductCode)
                .containsExactly("A", "B");
        assertThat(result.get(0).getInvoiceFelUuid()).isEqualTo("U-500");
        assertThat(result.get(1).getItems()).hasSize(1);
        assertThat(result.get(1).getInvoiceStatus()).isEqualTo("PENDING");
        assertThat(result.get(2).getInvoiceId()).isNull();

        // Una consulta por lote, sin una por venta.
        verify(itemRepository, times(1)).findByOnlineSaleIdInOrderByIdAsc(any());
        verify(itemRepository, never()).findByOnlineSaleIdOrderByIdAsc(anyLong());
        verify(taxInvoiceRepository, times(1)).findAllById(any());
        verify(taxInvoiceRepository, times(1)).findBySourceTypeAndSourceIdInOrderByIdDesc(eq("ONLINE_SALE"), any());
        verify(taxInvoiceRepository, never()).findById(anyLong());
        verify(taxInvoiceRepository, never()).findBySourceTypeAndSourceId(any(), anyLong());
    }

    @Test
    void getByDate_sinVentasNoConsultaNada() {
        when(saleRepository.findBySaleDateOrderByIdAsc(any())).thenReturn(List.of());

        assertThat(service.getByDate(LocalDate.of(2026, 10, 13))).isEmpty();

        verify(itemRepository, never()).findByOnlineSaleIdInOrderByIdAsc(any());
        verify(taxInvoiceRepository, never()).findAllById(any());
    }

    @Test
    void ventaSinItemsUsaLosCamposLegacyDeLaVenta() {
        OnlineSaleEntity legacy = sale(5L, null);
        legacy.setProductId(9L);
        legacy.setProductCode("LEG");
        legacy.setQuantity(2);
        legacy.setUnitPrice(new BigDecimal("50.00"));
        when(saleRepository.findBySaleDateOrderByIdAsc(any())).thenReturn(List.of(legacy));
        when(itemRepository.findByOnlineSaleIdInOrderByIdAsc(any())).thenReturn(List.of());

        List<OnlineSaleResponse> result = service.getByDate(LocalDate.of(2026, 10, 13));

        assertThat(result.get(0).getItems()).hasSize(1);
        assertThat(result.get(0).getItems().get(0).getProductCode()).isEqualTo("LEG");
        assertThat(result.get(0).getItems().get(0).getSubtotal()).isEqualByComparingTo("100.00");
    }
}
