package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ShipmentNumberCounterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ShipmentNumberCounterRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.DatabaseMetaData;
import java.util.List;

/**
 * Correlativos LF_ENVP / LF_OPC. En Postgres: {@code UPDATE ... RETURNING} en la misma
 * transacción del envío (un rollback no quema el número). En H2 (tests) se incrementa
 * con lock pesimista, equivalente para rollback. Falta la fila → BusinessException.
 */
@Service
public class ShipmentNumberCounterService {

    public static final String LF_ENVP = "LF_ENVP";
    public static final String LF_OPC = "LF_OPC";

    private final ShipmentNumberCounterRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    public ShipmentNumberCounterService(ShipmentNumberCounterRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public long nextValue(String type) throws BusinessException {
        if (isPostgreSQL()) {
            return incrementReturning(type);
        }
        return incrementLocked(type);
    }

    @Transactional
    public String nextEnvp() throws BusinessException {
        return String.format("ENVP-%05d", nextValue(LF_ENVP));
    }

    @Transactional
    public long nextOpcSequence() throws BusinessException {
        return nextValue(LF_OPC);
    }

    private long incrementReturning(String type) throws BusinessException {
        Query query = entityManager.createNativeQuery(
                "UPDATE shipment_number_counter SET last_value = last_value + 1 "
                        + "WHERE type = :type RETURNING last_value");
        query.setParameter("type", type);
        List<?> rows = query.getResultList();
        if (rows == null || rows.isEmpty() || rows.get(0) == null) {
            throw missingRow(type);
        }
        return ((Number) rows.get(0)).longValue();
    }

    private long incrementLocked(String type) throws BusinessException {
        ShipmentNumberCounterEntity row = repository.findWithLockByType(type)
                .orElseThrow(() -> missingRow(type));
        long next = (row.getLastValue() == null ? 0L : row.getLastValue()) + 1;
        row.setLastValue(next);
        repository.save(row);
        entityManager.flush();
        return next;
    }

    private boolean isPostgreSQL() {
        try {
            return entityManager.unwrap(Session.class).doReturningWork(connection -> {
                DatabaseMetaData meta = connection.getMetaData();
                String name = meta.getDatabaseProductName();
                return name != null && name.toLowerCase().contains("postgres");
            });
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static BusinessException missingRow(String type) {
        return new BusinessException(
                "No existe el correlativo de envíos '" + type
                        + "'. Ejecute scripts/shipment-number-sequence.sql.");
    }
}
