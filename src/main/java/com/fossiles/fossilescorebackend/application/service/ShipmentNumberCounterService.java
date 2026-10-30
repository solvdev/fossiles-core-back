package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Correlativos LF_ENVP / LF_OPC. Un {@code UPDATE ... RETURNING} en la misma transacción
 * del envío: un rollback no quema el número. Falta la fila → BusinessException en español.
 */
@Service
public class ShipmentNumberCounterService {

    public static final String LF_ENVP = "LF_ENVP";
    public static final String LF_OPC = "LF_OPC";

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public long nextValue(String type) throws BusinessException {
        Query query = entityManager.createNativeQuery(
                "UPDATE shipment_number_counter SET last_value = last_value + 1 "
                        + "WHERE type = :type RETURNING last_value");
        query.setParameter("type", type);
        List<?> rows = query.getResultList();
        if (rows == null || rows.isEmpty() || rows.get(0) == null) {
            throw new BusinessException(
                    "No existe el correlativo de envíos '" + type
                            + "'. Ejecute scripts/shipment-number-sequence.sql.");
        }
        return ((Number) rows.get(0)).longValue();
    }

    public String nextEnvp() throws BusinessException {
        return String.format("ENVP-%05d", nextValue(LF_ENVP));
    }

    public long nextOpcSequence() throws BusinessException {
        return nextValue(LF_OPC);
    }
}
