package com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon;

import java.util.EnumSet;
import java.util.Set;

/** Catálogos de la conciliación de ventas en línea. Los nombres coinciden con los CHECK de migration-reconciliation-base.sql. */
public final class Recon {

    private Recon() {
    }

    public enum Status {
        PENDIENTE_REVISION, CONCILIADO_AUTO, CONCILIADO_MANUAL, CON_DIFERENCIAS, INFORMACION_INCOMPLETA,
        POSIBLE_DUPLICADO, REQUIERE_AUTORIZACION, CONCILIADO_CON_AJUSTE, EXCLUIDO;

        /** Estados que pueden entrar a un lote y descargarse. */
        public static final Set<Status> RECONCILED = EnumSet.of(CONCILIADO_AUTO, CONCILIADO_MANUAL, CONCILIADO_CON_AJUSTE);

        public boolean isReconciled() {
            return RECONCILED.contains(this);
        }
    }

    public enum Origin { NORMAL, HISTORICO }

    public enum Severity { CRITICAL, WARNING }

    public enum Outcome { PASS, FAIL, NA }

    public enum SourceType { CARRIER, BANK, CARD, CASH, MANUAL }

    public enum MatchMethod { GUIDE, REFERENCE, AUTH, ENVL, MANUAL }

    public enum RowStatus { ACTIVE, VOID }

    public enum BatchStatus { OPEN, CLOSED, VOID }

    public enum DifferenceType {
        MONTO_INCORRECTO, REFERENCIA_NO_ENCONTRADA, ENVIO_SIN_VENTA, VENTA_SIN_ENVIO, PAGO_DUPLICADO,
        FECHA_INCONSISTENTE, COMISION_INCORRECTA, DATOS_INSUFICIENTES, PENDIENTE_AUTORIZACION
    }

    public enum DifferenceStatus { OPEN, PROPOSED, AUTHORIZED, REJECTED, CORRECTED }

    public enum CommissionStatus { CALCULADA, ANULADA_DEVOLUCION, CONGELADA }

    public enum LedgerType { CARGO, DESCARGA, AJUSTE_AUTORIZADO, REVERSA }
}
