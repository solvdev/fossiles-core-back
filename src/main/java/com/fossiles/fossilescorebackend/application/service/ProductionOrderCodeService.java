package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProductionOrderCodeService {

    private final ProductionOrderRepository productionOrderRepository;

    public String generateNextCode(String orderType) throws BusinessException {
        String prefix = getOrderCodePrefix(orderType);
        if (prefix == null) {
            throw new BusinessException("No existe prefijo configurado para el tipo de orden: " + orderType);
        }
        int next = getNextCorrelative(prefix);
        return prefix + "-" + next;
    }

    public String getOrderCodePrefix(String orderType) {
        if ("NORMAL".equals(orderType)) return "OPK";
        if ("MARCAS".equals(orderType)) return "OPV";
        if ("OPV".equals(orderType)) return "OPV";
        if ("INTERNA".equals(orderType)) return "OPI";
        if ("CINCHOS".equals(orderType)) return "OPC";
        if ("DISTRIBUTION".equals(orderType)) return "OPD";
        if ("VENTA_EN_LINEA".equals(orderType)) return "OPL";
        return null;
    }

    public int getNextCorrelative(String prefix) {
        Pattern pattern = Pattern.compile("^" + Pattern.quote(prefix) + "-(\\d+)$");
        return productionOrderRepository.findAll().stream()
                .map(ProductionOrderEntity::getCode)
                .filter(Objects::nonNull)
                .map(pattern::matcher)
                .filter(Matcher::matches)
                .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                .max()
                .orElse(0) + 1;
    }
}
