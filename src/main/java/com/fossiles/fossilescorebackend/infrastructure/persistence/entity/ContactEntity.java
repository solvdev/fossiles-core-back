package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "contact")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 150)
    private String name;

    @Column(length = 30)
    private String phone;

    @Column(length = 120)
    private String email;

    @Column(unique = true, length = 30)
    private String nit;

    @Column(length = 30)
    private String type; // supplier | customer | other
}

