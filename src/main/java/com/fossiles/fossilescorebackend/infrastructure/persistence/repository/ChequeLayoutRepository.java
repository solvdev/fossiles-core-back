package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.BankAccountEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ChequeLayoutEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PaymentMethodEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierPaymentAllocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierPaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;

@Repository
public interface ChequeLayoutRepository extends JpaRepository<ChequeLayoutEntity, Long> {
    List<ChequeLayoutEntity> findAllByOrderByNameAsc();
}
