package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayableDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PayableDocumentRepository extends JpaRepository<PayableDocumentEntity, Long> {

    List<PayableDocumentEntity> findByStatus(String status);

    /** Las rentas ya generadas de un mes (las anuladas no cuentan: se pueden volver a generar). */
    List<PayableDocumentEntity> findByKindAndPeriodAndStatus(String kind, String period, String status);
}
