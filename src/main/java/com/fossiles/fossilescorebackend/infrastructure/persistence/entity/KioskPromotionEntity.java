package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "kiosk_promotion")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPromotionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 160, nullable = false)
    private String name;

    @Column(name = "description", length = 600)
    private String description;

    @Column(name = "discount_type", length = 20, nullable = false)
    private String discountType;

    @Column(name = "discount_value", precision = 12, scale = 2, nullable = false)
    private BigDecimal discountValue;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "active")
    private Boolean active;

    /** Kiosco único (columna vieja). Se respeta si no hay lista; null y sin lista = todos los kioscos. */
    @Column(name = "kiosk_location_id")
    private Long kioskLocationId;

    /** Kioscos donde aplica, ids separados por coma ("3,7,12"). null o vacío = todos (incluidos los nuevos). */
    @Column(name = "kiosk_location_ids", columnDefinition = "TEXT")
    private String kioskLocationIds;

    /** DAMA o CABALLERO; null = aplica a todo el carrito. */
    @Column(name = "audience_category", length = 20)
    private String audienceCategory;

    @Column(name = "combo_buy_qty")
    private Integer comboBuyQty;

    @Column(name = "combo_pay_qty")
    private Integer comboPayQty;

    /** Regla dinámica (JSON de PromotionRuleConfig) para discount_type = RULES. */
    @Column(name = "rule_config", columnDefinition = "TEXT")
    private String ruleConfig;

    /** DRAFT = borrador (solo simulador), APPROVED = vigente en el POS. null = APPROVED (promos viejas). */
    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @OneToMany(mappedBy = "promotion", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<KioskPromotionTierEntity> tiers = new ArrayList<>();

    /** Kioscos donde aplica ya resueltos (lista nueva, o el kiosco único viejo). Vacío = todos. */
    public Set<Long> effectiveKioskIds() {
        Set<Long> ids = parseKioskIds(kioskLocationIds);
        if (ids.isEmpty() && kioskLocationId != null) {
            ids.add(kioskLocationId);
        }
        return ids;
    }

    /** true si la promoción aplica a ese kiosco (sin restricción = aplica a todos). */
    public boolean appliesToKiosk(Long kioskId) {
        Set<Long> ids = effectiveKioskIds();
        return ids.isEmpty() || (kioskId != null && ids.contains(kioskId));
    }

    /** Guarda los kioscos: vacío = todos; con uno solo también llena la columna vieja. */
    public void assignKiosks(Collection<Long> kioskIds) {
        Set<Long> ids = new LinkedHashSet<>();
        if (kioskIds != null) {
            kioskIds.stream().filter(Objects::nonNull).forEach(ids::add);
        }
        this.kioskLocationIds = ids.isEmpty()
                ? null
                : ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        this.kioskLocationId = ids.size() == 1 ? ids.iterator().next() : null;
    }

    public static Set<Long> parseKioskIds(String raw) {
        Set<Long> ids = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(token));
            } catch (NumberFormatException ignored) {
                // id inválido: se ignora
            }
        }
        return ids;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (active == null) {
            active = true;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
