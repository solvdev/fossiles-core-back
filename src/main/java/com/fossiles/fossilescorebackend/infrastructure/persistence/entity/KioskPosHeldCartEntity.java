package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Carrito del POS guardado en espera. No es una venta: solo conserva el JSON del carrito del front. */
@Entity
@Table(name = "kiosk_pos_held_cart")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPosHeldCartEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kiosk_location_id", nullable = false)
    private Long kioskLocationId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "label", length = 160)
    private String label;

    @Column(name = "cart_json", columnDefinition = "TEXT", nullable = false)
    private String cartJson;

    @Column(name = "held_date", nullable = false)
    private LocalDate heldDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = GuatemalaDateTime.now();
        }
        if (heldDate == null) {
            heldDate = GuatemalaDateTime.today();
        }
    }
}
