package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.model.TaxInvoiceDocument;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlineSaleInvoiceMapperTest {

    @Mock
    private OnlineSaleItemRepository itemRepository;

    @InjectMocks
    private OnlineSaleInvoiceMapper mapper;

    @Test
    void totalsFromAllItemsAndShippingNotLegacySaleTotal() {
        OnlineSaleEntity sale = OnlineSaleEntity.builder()
                .id(7L)
                .saleNumber("7")
                .totalAmount(new BigDecimal("310.00"))
                .netAmount(new BigDecimal("295.00"))
                .shippingCost(new BigDecimal("15.00"))
                .invoiceTaxId("7969244")
                .customerName("JESSICA MARIBEL MONTEROS ALVARADO")
                .build();

        when(itemRepository.findByOnlineSaleIdOrderByIdAsc(7L)).thenReturn(List.of(
                OnlineSaleItemEntity.builder()
                        .productCode("B-62H")
                        .productName("BILLETERA SLIDE CABALLERO CAFE")
                        .quantity(1)
                        .unitPrice(new BigDecimal("295.00"))
                        .subtotal(new BigDecimal("295.00"))
                        .build(),
                OnlineSaleItemEntity.builder()
                        .productCode("FOSS-6")
                        .productName("CINCHO FOSS 6 34")
                        .quantity(1)
                        .unitPrice(new BigDecimal("305.00"))
                        .subtotal(new BigDecimal("305.00"))
                        .build()
        ));

        TaxInvoiceDocument document = mapper.fromSale(sale);

        assertThat(document.getLines()).hasSize(3);
        assertThat(document.getTotalAmount()).isEqualByComparingTo("615.00");
        assertThat(document.getSubtotal()).isEqualByComparingTo("615.00");
    }

    @Test
    void usesTodayAsIssuedAtNotSaleDate() {
        OnlineSaleEntity sale = OnlineSaleEntity.builder()
                .id(9L)
                .saleNumber("9")
                .saleDate(LocalDate.of(2020, 6, 24))
                .totalAmount(new BigDecimal("100.00"))
                .netAmount(new BigDecimal("100.00"))
                .invoiceTaxId("CF")
                .customerName("CONSUMIDOR FINAL")
                .build();

        when(itemRepository.findByOnlineSaleIdOrderByIdAsc(9L)).thenReturn(List.of(
                OnlineSaleItemEntity.builder()
                        .productName("Producto")
                        .quantity(1)
                        .unitPrice(new BigDecimal("100.00"))
                        .subtotal(new BigDecimal("100.00"))
                        .build()
        ));

        TaxInvoiceDocument document = mapper.fromSale(sale);

        assertThat(document.getIssuedAt().toLocalDate()).isEqualTo(GuatemalaDateTime.today());
        assertThat(document.getIssuedAt().toLocalDate()).isNotEqualTo(sale.getSaleDate());
    }
}
