package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;

/** Resultado de una regla: qué se esperaba, qué se encontró, por qué falla y qué hacer. */
public record RuleResult(
        String code,
        String name,
        Recon.Severity severity,
        Recon.Outcome outcome,
        String expected,
        String found,
        String message,
        String action) {

    public boolean failed() {
        return outcome == Recon.Outcome.FAIL;
    }

    public boolean criticalFailure() {
        return failed() && severity == Recon.Severity.CRITICAL;
    }
}
