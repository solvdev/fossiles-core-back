package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Historial de una tarea: solo se agrega, nunca se edita ni se borra. */
@Entity
@Table(name = "task_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskEventEntity {

    public static final String START = "START";
    public static final String PAUSE = "PAUSE";
    public static final String RESUME = "RESUME";
    public static final String COMPLETE = "COMPLETE";
    public static final String AWAITING_WAREHOUSE = "AWAITING_WAREHOUSE";
    public static final String CANCEL = "CANCEL";
    public static final String RESET = "RESET";
    public static final String ITEMS_ADDED = "ITEMS_ADDED";
    public static final String DELAY_MARKED = "DELAY_MARKED";
    public static final String DELAY_FORGIVEN = "DELAY_FORGIVEN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(length = 500)
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
