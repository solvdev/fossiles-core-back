package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.FelCertificationResult;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * @deprecated Usar {@link TaxInvoiceService} directamente.
 */
@Service
@RequiredArgsConstructor
@Deprecated
public class FelPosInvoiceService {

    private final TaxInvoiceService taxInvoiceService;

    public FelCertificationResult issueForSale(KioskSaleEntity sale) throws BusinessException {
        return issueForSale(sale, true);
    }

    public FelCertificationResult issueForSale(KioskSaleEntity sale, boolean requestInvoice) throws BusinessException {
        TaxInvoiceResponse response = taxInvoiceService.issueFromKioskSale(sale, requestInvoice);
        if (response == null) {
            return FelCertificationResult.builder().status("SKIPPED").build();
        }
        return FelCertificationResult.builder()
                .status(response.getStatus())
                .uuid(response.getFelUuid())
                .serie(response.getFelSerie())
                .numero(response.getFelNumero())
                .errorMessage(response.getFelError())
                .build();
    }
}
