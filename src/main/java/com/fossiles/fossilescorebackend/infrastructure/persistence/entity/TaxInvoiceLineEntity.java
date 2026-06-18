package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "tax_invoice_line")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxInvoiceLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tax_invoice_id", nullable = false)
    private TaxInvoiceEntity taxInvoice;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    @Column(name = "description", length = 500, nullable = false)
    private String description;

    @Column(name = "quantity", precision = 12, scale = 3, nullable = false)
    private BigDecimal quantity;

    @Column(name = "unit_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "line_total", precision = 12, scale = 2, nullable = false)
    private BigDecimal lineTotal;

    @Column(name = "gravable_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal gravableAmount;

    @Column(name = "tax_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal taxAmount;

    @PrePersist
    @PreUpdate
    protected void normalizeDefaults() {
        if (lineNumber == null) {
            lineNumber = 1;
        }
        if (quantity == null) {
            quantity = BigDecimal.ONE;
        }
        if (unitPrice == null) {
            unitPrice = BigDecimal.ZERO;
        }
        if (lineTotal == null) {
            lineTotal = BigDecimal.ZERO;
        }
        if (gravableAmount == null) {
            gravableAmount = BigDecimal.ZERO;
        }
        if (taxAmount == null) {
            taxAmount = BigDecimal.ZERO;
        }
    }
}
