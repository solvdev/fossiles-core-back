package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DocumentSeriesEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.DocumentSeriesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Correlativo TK-##### para tareas de producción (serie con lock pesimista).
 * Antes duplicado en TaskController y ProductionTaskGenerationService.
 */
@Service
@RequiredArgsConstructor
public class TaskCodeGenerator {

    private final DocumentSeriesRepository documentSeriesRepository;

    public String generateTaskCode() throws BusinessException {
        String documentType = "TASK";
        String series = "TK";

        DocumentSeriesEntity seriesEntity = documentSeriesRepository
                .findByDocumentTypeAndSeriesForUpdate(documentType, series)
                .orElseGet(() -> {
                    DocumentSeriesEntity newSeries = DocumentSeriesEntity.builder()
                            .documentType(documentType)
                            .series(series)
                            .currentCorrelative(0L)
                            .status("active")
                            .description("Serie automática para tareas de producción")
                            .build();
                    return documentSeriesRepository.save(newSeries);
                });

        documentSeriesRepository.incrementCorrelative(seriesEntity.getId());
        seriesEntity.setCurrentCorrelative(seriesEntity.getCurrentCorrelative() + 1);
        documentSeriesRepository.save(seriesEntity);

        return String.format("%s-%05d", series, seriesEntity.getCurrentCorrelative());
    }
}
