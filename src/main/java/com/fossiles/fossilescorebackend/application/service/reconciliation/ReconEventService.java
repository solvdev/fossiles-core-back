package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconEventEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.ReconEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Historial de solo agregar: toda acción relevante deja un evento con quién, cuándo y qué cambió. */
@Service
@RequiredArgsConstructor
public class ReconEventService {

    private final ReconEventRepository eventRepository;
    private final ReconActor actor;
    private final ObjectMapper objectMapper;

    public ReconEventEntity record(Long caseId, Long batchId, String type, Recon.Status from, Recon.Status to,
                                   Map<String, Object> payload) {
        return eventRepository.save(ReconEventEntity.builder()
                .caseId(caseId)
                .batchId(batchId)
                .eventType(type)
                .fromStatus(from == null ? null : from.name())
                .toStatus(to == null ? null : to.name())
                .payload(toJson(payload))
                .actorId(actor.id())
                .build());
    }

    private String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return String.valueOf(payload);
        }
    }
}
