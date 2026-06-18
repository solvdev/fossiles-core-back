package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestEntity;

import java.util.List;

public interface InternalShipmentRequestRepositoryCustom {

    List<InternalShipmentRequestEntity> findFiltered(String status, String requestType);
}
