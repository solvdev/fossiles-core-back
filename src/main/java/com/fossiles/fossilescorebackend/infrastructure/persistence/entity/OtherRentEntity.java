package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Renta que paga la empresa y que no es un kiosco (oficina, bodega u otra). El monto se guarda aquí.
 * El índice único parcial de categoría + nombre (solo activas) vive en el SQL: H2 no lo replica y
 * {@code ddl-auto=validate} no lo exige. El servicio también lo valida.
 */
@Entity
@Table(name = "other_rent")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OtherRentEntity {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_EXCEL = "EXCEL";

    public static final String CATEGORY_OFICINA = "OFICINA";
    public static final String CATEGORY_BODEGA = "BODEGA";
    public static final String CATEGORY_OTRO = "OTRO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "category", nullable = false, length = 20)
    private String category;

    @Column(name = "landlord_name", length = 200)
    private String landlordName;

    @Column(name = "monthly_amount", precision = 12, scale = 2)
    private BigDecimal monthlyAmount;

    @Column(name = "payment_day")
    private Integer paymentDay;

    @Column(name = "contract_start")
    private LocalDate contractStart;

    @Column(name = "contract_end")
    private LocalDate contractEnd;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

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
        if (source == null) {
            source = SOURCE_MANUAL;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = GuatemalaDateTime.now();
    }
}
