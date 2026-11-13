package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Novedad de un colaborador que cambia lo que cobra: horas extra, un feriado trabajado, ausencias sin goce de sueldo
 * o vacaciones. La planilla la toma si su fecha cae en el período que calcula.
 */
@Entity
@Table(name = "payroll_incident")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayrollIncidentEntity {
    public static final String KIND_OVERTIME = "HORA_EXTRA";
    public static final String KIND_HOLIDAY_WORKED = "FERIADO_TRABAJADO";
    public static final String KIND_ABSENCE = "AUSENCIA";
    public static final String KIND_VACATION = "VACACIONES";
    public static final String STATUS_ACTIVE = "ACTIVA";
    public static final String STATUS_CANCELLED = "ANULADA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(nullable = false, length = 30)
    private String kind;

    /** Horas extra y feriado: el día. Ausencia y vacaciones: el primer día. */
    @Column(name = "date_from", nullable = false)
    private LocalDate dateFrom;

    /** Igual a {@code dateFrom} en horas extra y feriado; en ausencia y vacaciones, el último día. */
    @Column(name = "date_to", nullable = false)
    private LocalDate dateTo;

    @Column(precision = 6, scale = 2)
    private BigDecimal hours;

    /** Pago por hora distinto del calculado con los parámetros; vacío usa el recargo vigente. */
    @Column(name = "hourly_rate_override", precision = 10, scale = 2)
    private BigDecimal hourlyRateOverride;

    @Column(name = "holiday_name", length = 100)
    private String holidayName;

    @Column(length = 300)
    private String note;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_ACTIVE;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = GuatemalaDateTime.now();
    }
}
