package com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Fondo recibido: una línea de liquidación, extracto, reporte de tarjeta o captura manual. */
@Entity
@Table(name = "recon_receipt")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconReceiptEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "import_file_id")
    private Long importFileId;
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private Recon.SourceType sourceType;
    @Column(name = "row_hash", length = 64)
    private String rowHash;
    @Column(name = "carrier", length = 50)
    private String carrier;
    @Column(name = "shipment_number", length = 30)
    private String shipmentNumber;
    @Column(name = "guide_number", length = 100)
    private String guideNumber;
    @Column(name = "reference", length = 100)
    private String reference;
    @Column(name = "authorization_number", length = 100)
    private String authorizationNumber;
    @Column(name = "provider_txn_id", length = 100)
    private String providerTxnId;
    @Column(name = "received_date")
    private LocalDate receivedDate;
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
    @Column(name = "shipping_amount", precision = 12, scale = 2)
    private BigDecimal shippingAmount;
    @Column(name = "third_party_fee", precision = 12, scale = 2)
    private BigDecimal thirdPartyFee;
    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private Recon.RowStatus status = Recon.RowStatus.ACTIVE;
    @Column(name = "void_reason", columnDefinition = "TEXT")
    private String voidReason;
    @Column(name = "voided_at")
    private LocalDateTime voidedAt;
    @Column(name = "voided_by")
    private Long voidedBy;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
