package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "company_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanySettingsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(length = 30)
    private String nit;

    @Column(name = "currency_default", length = 10)
    private String currencyDefault;

    @Column(length = 50)
    private String timezone;
}

