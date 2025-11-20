package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "document_series")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentSeriesEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_type", length = 50)
    private String docType;

    @Column(length = 20)
    private String prefix;

    @Column(name = "current_number")
    private Integer currentNumber;
}

