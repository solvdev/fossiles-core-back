package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos;
import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.CaseFilter;
import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.CasePage;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.reconciliation.*;
import com.fossiles.fossilescorebackend.infrastructure.config.GlobalExceptionHandler;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Contrato HTTP: permisos validados antes del servicio, filtros bien leídos y descarga con su huella. */
class ReconciliationControllerTest {

    private final ReconciliationAccessGuard guard = Mockito.mock(ReconciliationAccessGuard.class);
    private final ReconciliationQueryService query = Mockito.mock(ReconciliationQueryService.class);
    private final ReconciliationBatchService batches = Mockito.mock(ReconciliationBatchService.class);
    private final ReconciliationReceiptService receipts = Mockito.mock(ReconciliationReceiptService.class);
    private final ReconciliationCaseService cases = Mockito.mock(ReconciliationCaseService.class);
    private final ReconciliationSettlementService settlement = Mockito.mock(ReconciliationSettlementService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ReconciliationController controller = new ReconciliationController(guard, cases, query,
                Mockito.mock(ReconciliationDashboardService.class), receipts, Mockito.mock(ReconciliationDifferenceService.class), batches, settlement,
                Mockito.mock(ReconciliationCommissionService.class), Mockito.mock(ReconciliationSellerService.class),
                Mockito.mock(ReconciliationGuideImportService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void bandeja_leeLosFiltrosCombinados() throws Exception {
        when(query.list(any(), anyInt(), anyInt(), any(), any())).thenReturn(new CasePage(List.of(), 0, 0, 50));

        mvc.perform(get("/api/reconciliation/cases").param("from", "2026-10-01").param("to", "2026-10-14").param("method", "CONTRA_ENTREGA")
                .param("status", "CON_DIFERENCIAS,INFORMACION_INCOMPLETA").param("q", "58123456789").param("withoutBatch", "true")
                .param("sort", "difference").param("dir", "asc")).andExpect(status().isOk());

        ArgumentCaptor<CaseFilter> captor = ArgumentCaptor.forClass(CaseFilter.class);
        verify(query).list(captor.capture(), eq(0), eq(50), eq("difference"), eq("asc"));
        CaseFilter f = captor.getValue();
        assertThat(f.statuses()).containsExactly(Recon.Status.CON_DIFERENCIAS, Recon.Status.INFORMACION_INCOMPLETA);
        assertThat(f.from().toString()).isEqualTo("2026-10-01");
        assertThat(f.method()).isEqualTo("CONTRA_ENTREGA");
        assertThat(f.q()).isEqualTo("58123456789");
        assertThat(f.withoutBatch()).isTrue();
    }

    @Test
    void filtroInvalido_respondeError400ConMensaje() throws Exception {
        mvc.perform(get("/api/reconciliation/cases").param("status", "INVENTADO")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Valor de filtro no válido")));
    }

    @Test
    void sinPermiso_noLlegaAlServicio() throws Exception {
        doThrow(new BusinessException("No tiene permiso para cerrar lotes.")).when(guard).assertCanClose();

        mvc.perform(post("/api/reconciliation/batches/9/close")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No tiene permiso para cerrar lotes."));

        Mockito.verifyNoInteractions(batches);
    }

    @Test
    void cierre_pasaLaClaveDeIdempotencia() throws Exception {
        mvc.perform(post("/api/reconciliation/batches/9/close").header("Idempotency-Key", "cierre-oct-1")).andExpect(status().isOk());

        verify(batches).close(9L, "cierre-oct-1");
    }

    @Test
    void descarga_devuelveExcelConSuHuella() throws Exception {
        when(batches.download(9L)).thenReturn(new ReconciliationBatchService.ExportFile(new byte[]{1, 2, 3}, "conciliacion-LC-202610-001.xlsx", "abc123"));

        mvc.perform(get("/api/reconciliation/batches/9/export")).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Sha256", "abc123"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"conciliacion-LC-202610-001.xlsx\""))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    void importarFondos_exigePermisoDeImportarYLeeElTipoDeFuente() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "forza.csv", "text/csv", "Guia,Monto\n1,2\n".getBytes());

        mvc.perform(multipart("/api/reconciliation/receipts/import").file(file).param("sourceType", "CARRIER")).andExpect(status().isOk());

        verify(guard).assertCanImport();
        verify(receipts).importFile(any(), eq("forza.csv"), eq(Recon.SourceType.CARRIER));
    }

    @Test
    void generarCasos_conFechaInvalida_pideElFormato() throws Exception {
        mvc.perform(post("/api/reconciliation/cases/generate").contentType("application/json").content("{\"from\":\"ayer\",\"to\":\"2026-10-14\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("AAAA-MM-DD")));
    }

    @Test
    void liquidar_exigePermisoDeCerrar_yPasaLasVentasElegidasYLaClave() throws Exception {
        mvc.perform(post("/api/reconciliation/batches/settle").header("Idempotency-Key", "liq-1").contentType("application/json")
                .content("{\"caseIds\":[4,5]}")).andExpect(status().isOk());

        verify(guard).assertCanClose();
        verify(settlement).settle(List.of(4L, 5L), "liq-1");
    }

    @Test
    void liquidarSinPermiso_noLlegaAlServicio() throws Exception {
        doThrow(new BusinessException("No tiene permiso para cerrar lotes.")).when(guard).assertCanClose();

        mvc.perform(post("/api/reconciliation/batches/settle").contentType("application/json").content("{\"caseIds\":[4]}"))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(settlement);
    }

    @Test
    void pagosEnBloque_exigenPermisoDeImportar() throws Exception {
        mvc.perform(post("/api/reconciliation/cases/receipts/bulk").contentType("application/json")
                .content("{\"items\":[{\"caseId\":4,\"matchExpected\":true,\"reference\":\"R-1\"},{\"caseId\":5,\"amount\":100.50}]}")).andExpect(status().isOk());

        verify(guard).assertCanImport();
        ArgumentCaptor<List<ReconciliationDtos.BulkReceiptItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(settlement).recordMany(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).matchExpected()).isTrue();
        assertThat(captor.getValue().get(1).amount()).isEqualByComparingTo("100.50");
    }

    @Test
    void corregirPago_exigePermisoDeImportarYConciliar() throws Exception {
        mvc.perform(put("/api/reconciliation/cases/7/receipt").contentType("application/json")
                .content("{\"amount\":120.00,\"matchExpected\":false,\"reference\":\"AUT-1\"}")).andExpect(status().isOk());

        verify(guard).assertCanImport();
        verify(guard).assertCanReconcile();
        ArgumentCaptor<ReconciliationDtos.ReplaceReceiptRequest> captor = ArgumentCaptor.forClass(ReconciliationDtos.ReplaceReceiptRequest.class);
        verify(settlement).replace(org.mockito.ArgumentMatchers.eq(7L), captor.capture());
        assertThat(captor.getValue().amount()).isEqualByComparingTo("120.00");
        assertThat(captor.getValue().reference()).isEqualTo("AUT-1");
    }

    @Test
    void envioYComision_exigenPermisoDeConciliar() throws Exception {
        Mockito.when(cases.updateShippingCommission(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mockito.mock(com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconCaseEntity.class));
        Mockito.when(query.rows(org.mockito.ArgumentMatchers.anyList())).thenReturn(java.util.Arrays.asList((ReconciliationDtos.CaseRow) null));
        mvc.perform(put("/api/reconciliation/cases/8/shipping-commission").contentType("application/json")
                .content("{\"realShipping\":25.00,\"commissionRate\":0.04}")).andExpect(status().isOk());

        verify(guard).assertCanReconcile();
        verify(cases).updateShippingCommission(org.mockito.ArgumentMatchers.eq(8L), org.mockito.ArgumentMatchers.argThat(v -> v.compareTo(new java.math.BigDecimal("25.00")) == 0),
                org.mockito.ArgumentMatchers.argThat(v -> v.compareTo(new java.math.BigDecimal("0.04")) == 0));
    }
}
