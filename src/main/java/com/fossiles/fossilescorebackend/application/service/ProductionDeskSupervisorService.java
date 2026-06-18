package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.DeskSupervisorAssignmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DeskSupervisorAssignmentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.DeskSupervisorDayResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionDeskSupervisorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionDeskSupervisorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ProductionDeskSupervisorService {

    private final ProductionDeskSupervisorRepository repository;
    private final ProductionDeskCountService productionDeskCountService;

    public DeskSupervisorDayResponse getDay(LocalDate effectiveDate) throws BusinessException {
        if (effectiveDate == null) {
            throw new BusinessException("La fecha efectiva es obligatoria.");
        }
        int numDesks = productionDeskCountService.getDay(effectiveDate).getNumDesks();
        List<DeskSupervisorAssignmentResponse> list = new ArrayList<>();
        for (int d = 1; d <= numDesks; d++) {
            list.add(DeskSupervisorAssignmentResponse.builder()
                    .desk(d)
                    .supervisorName(resolveSupervisorNameForDesk(d, effectiveDate))
                    .build());
        }
        return DeskSupervisorDayResponse.builder()
                .effectiveDate(effectiveDate)
                .numDesks(numDesks)
                .assignments(list)
                .build();
    }

    @Transactional
    public DeskSupervisorDayResponse replaceDay(LocalDate effectiveDate, List<DeskSupervisorAssignmentRequest> body)
            throws BusinessException {
        if (effectiveDate == null) {
            throw new BusinessException("La fecha efectiva es obligatoria.");
        }
        int numDesks = productionDeskCountService.getDay(effectiveDate).getNumDesks();
        if (body == null) {
            body = List.of();
        }

        Set<Integer> seen = new HashSet<>();
        for (DeskSupervisorAssignmentRequest row : body) {
            if (row == null || row.getDesk() == null) {
                throw new BusinessException("Cada fila debe incluir el número de mesa.");
            }
            int desk = row.getDesk();
            if (desk < 1 || desk > numDesks) {
                throw new BusinessException("Mesa fuera de rango: " + desk + " (mesas activas: 1.." + numDesks + ").");
            }
            if (!seen.add(desk)) {
                throw new BusinessException("Mesa duplicada en el cuerpo: " + desk);
            }
        }

        repository.deleteByEffectiveDate(effectiveDate);

        for (DeskSupervisorAssignmentRequest row : body) {
            String name = row.getSupervisorName() == null ? "" : row.getSupervisorName().trim();
            if (name.isEmpty()) {
                continue;
            }
            repository.save(ProductionDeskSupervisorEntity.builder()
                    .desk(row.getDesk())
                    .effectiveDate(effectiveDate)
                    .supervisorName(name)
                    .build());
        }

        return getDay(effectiveDate);
    }

    /**
     * Encargado vigente para una mesa en una fecha: el último guardado con fecha efectiva &lt;= asOfDate.
     * Los cambios aplican hacia adelante hasta un nuevo guardado en otra fecha.
     */
    public String resolveSupervisorNameForDesk(Integer desk, LocalDate asOfDate) {
        if (desk == null || asOfDate == null) {
            return "";
        }
        return repository
                .findTopByDeskAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc(desk, asOfDate)
                .map(e -> Optional.ofNullable(e.getSupervisorName()).orElse("").trim())
                .orElse("");
    }
}
