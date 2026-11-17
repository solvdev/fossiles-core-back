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

import java.time.LocalDateTime;

/** Cuenta con la que se paga (banco o caja): su cuenta contable, su chequera y el formato de sus cheques. */
@Entity
@Table(name = "bank_account")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BankAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "account_number", length = 60)
    private String accountNumber;

    @Column(name = "ledger_code", nullable = false, length = 30)
    private String ledgerCode;

    @Column(name = "ledger_name", length = 120)
    private String ledgerName;

    /** Desde dónde empieza la chequera mientras la cuenta no tenga cheques usados. */
    @Column(name = "first_cheque_number", length = 30)
    private String firstChequeNumber;

    /** La chequera también la usa Planilla: sus cheques cuentan para la numeración y para no repetir. */
    @Column(name = "shares_payroll_checks", nullable = false)
    @Builder.Default
    private Boolean sharesPayrollChecks = false;

    @Column(name = "cheque_layout_id")
    private Long chequeLayoutId;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

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
