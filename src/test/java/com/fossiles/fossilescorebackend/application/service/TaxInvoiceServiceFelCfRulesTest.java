package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.config.FelEmissionProperties;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationInternalNumberSequenceRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaxInvoiceRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaxInvoiceServiceFelCfRulesTest {

    @Mock private TaxInvoiceRepository taxInvoiceRepository;
    @Mock private KioskSaleRepository kioskSaleRepository;
    @Mock private OnlineSaleRepository onlineSaleRepository;
    @Mock private LocationRepository locationRepository;
    @Mock private LocationInternalNumberSequenceRepository locationInternalNumberSequenceRepository;
    @Mock private OnlineSaleService onlineSaleService;
    @Mock private KioskSaleInvoiceMapper kioskSaleInvoiceMapper;
    @Mock private OnlineSaleInvoiceMapper onlineSaleInvoiceMapper;
    @Spy private FelEmissionProperties properties = new FelEmissionProperties();
    @Mock private FelFactXmlBuilder factXmlBuilder;
    @Mock private FelAnulacionXmlBuilder anulacionXmlBuilder;
    @Mock private FelSignerService signerService;
    @Mock private FelCertificationService certificationService;
    @Mock private FelReceptorLookupService receptorLookupService;
    @Mock private SecurityUtil securityUtil;
    @Mock private TaxInvoiceAttemptService taxInvoiceAttemptService;
    @Mock private TaxInvoiceAccessGuard taxInvoiceAccessGuard;

    @InjectMocks
    private TaxInvoiceService taxInvoiceService;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setCreditDebitNotesEnabled(false);
        properties.setNitEmisor("11700874K");
        properties.setNombreEmisor("EMISOR SA");
        properties.setDireccion("Ciudad");
    }

    @Test
    void voidInvoice_cfOutsideWindow_rejectsBeforeCertify() throws Exception {
        LocalDateTime issuedAt = GuatemalaDateTime.today().minusDays(2).atTime(10, 0);
        TaxInvoiceEntity invoice = certifiedCfInvoice(99L, issuedAt);
        when(taxInvoiceRepository.findById(99L)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> taxInvoiceService.voidInvoice(99L, "Error de venta"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Consumidor Final");

        verify(certificationService, never()).certifyAnnulmentSignedXml(any(), any(), any());
        verify(anulacionXmlBuilder, never()).buildUnsignedAnulacionXml(any(), any(), any(), any(), any());
    }

    @Test
    void voidInvoiceFromPos_doesNotRequireAccountingPermission() throws Exception {
        LocalDateTime issuedAt = GuatemalaDateTime.today().atTime(10, 0);
        TaxInvoiceEntity invoice = certifiedCfInvoice(77L, issuedAt);
        when(taxInvoiceRepository.findById(77L)).thenReturn(Optional.of(invoice));
        properties.setEnabled(false);

        taxInvoiceService.voidInvoiceFromPos(77L, "Corrección de venta");

        verify(taxInvoiceAccessGuard, never()).assertCanEditFelMetadata();
        verify(taxInvoiceRepository).save(invoice);
        assertThat(invoice.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void voidInvoice_requiresAccountingPermission() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException("No tiene permiso"))
                .when(taxInvoiceAccessGuard).assertCanEditFelMetadata();

        assertThatThrownBy(() -> taxInvoiceService.voidInvoice(1L, "motivo"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No tiene permiso");

        verify(taxInvoiceRepository, never()).findById(any());
    }

    private static TaxInvoiceEntity certifiedCfInvoice(Long id, LocalDateTime issuedAt) {
        return TaxInvoiceEntity.builder()
                .id(id)
                .sourceType("KIOSK_SALE")
                .status("CERTIFIED")
                .documentType("FACT")
                .customerTaxId("CF")
                .customerName("CONSUMIDOR FINAL")
                .felUuid("A1B2C3D4-E5F6-7890-ABCD-EF1234567890")
                .issuedAt(issuedAt)
                .subtotal(BigDecimal.TEN)
                .discountAmount(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ONE)
                .totalAmount(BigDecimal.TEN)
                .build();
    }

    @Test
    void resolveDocumentType_ncreWithFlagOff_rejectsWithoutSilencingToFact() throws Exception {
        Method method = TaxInvoiceService.class.getDeclaredMethod("resolveDocumentType", String.class);
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(taxInvoiceService, "NCRE"))
                .hasCauseInstanceOf(BusinessException.class)
                .cause()
                .hasMessageContaining("no están habilitadas");
    }

    @Test
    void assertDocumentTypeAllowedForEmission_ncreCfWithFlagOn_rejectsSat224() throws Exception {
        properties.setCreditDebitNotesEnabled(true);
        Method method = TaxInvoiceService.class.getDeclaredMethod(
                "assertDocumentTypeAllowedForEmission", String.class, String.class);
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(taxInvoiceService, "NCRE", "CF"))
                .hasCauseInstanceOf(BusinessException.class)
                .cause()
                .hasMessageContaining("2.2.4");
    }

    @Test
    void assertDocumentTypeAllowedForEmission_ncreNitWithFlagOn_allowed() throws Exception {
        properties.setCreditDebitNotesEnabled(true);
        Method method = TaxInvoiceService.class.getDeclaredMethod(
                "assertDocumentTypeAllowedForEmission", String.class, String.class);
        method.setAccessible(true);

        Object result = method.invoke(taxInvoiceService, "NCRE", "11700874K");
        assertThat(result).isNull();
    }

    @Test
    void resolveDocumentType_ncreWithFlagOn_returnsNcre() throws Exception {
        properties.setCreditDebitNotesEnabled(true);
        Method method = TaxInvoiceService.class.getDeclaredMethod("resolveDocumentType", String.class);
        method.setAccessible(true);

        assertThat(method.invoke(taxInvoiceService, "NCRE")).isEqualTo("NCRE");
    }
}
