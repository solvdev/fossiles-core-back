package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Contrato de renta de un kiosco: arrendador, día de pago y vigencia. El monto sale de Finanzas (ALQUILER). */
@Entity
@Table(name = "kiosk_rent")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskRentEntity {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_EXCEL = "EXCEL";

    @Id
    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "landlord_name", length = 200)
    private String landlordName;

    @Column(name = "payment_day")
    private Integer paymentDay;

    @Column(name = "contract_start")
    private LocalDate contractStart;

    @Column(name = "contract_end")
    private LocalDate contractEnd;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "source", nullable = false, length = 10)
    @Builder.Default
    private String source = SOURCE_MANUAL;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = GuatemalaDateTime.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = GuatemalaDateTime.now();
    }
}
