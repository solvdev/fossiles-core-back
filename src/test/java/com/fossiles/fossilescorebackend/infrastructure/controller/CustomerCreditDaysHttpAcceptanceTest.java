package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.infrastructure.config.GlobalExceptionHandler;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** creditDays 0 and 60 are HTTP 200/201. -1 and 61 are HTTP 400. */
class CustomerCreditDaysHttpAcceptanceTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CustomerRepository customers = mock(CustomerRepository.class);
        when(customers.findByLegacyCode(any())).thenReturn(Optional.empty());
        when(customers.findById(1L)).thenReturn(Optional.of(CustomerEntity.builder()
                .id(1L)
                .name("Cliente")
                .legacyCode("HTTP1")
                .status("active")
                .creditDays(0)
                .build()));
        when(customers.save(any(CustomerEntity.class))).thenAnswer(invocation -> {
            CustomerEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(1L);
            }
            return entity;
        });
        mvc = MockMvcBuilders.standaloneSetup(new CustomerController(customers))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void zeroAndSixtyAreAccepted() throws Exception {
        mvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creditDays").value(0));
        mvc.perform(put("/api/customers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(60)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditDays").value(60));
    }

    @Test
    void outsideTheRangeIsBadRequest() throws Exception {
        mvc.perform(put("/api/customers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(-1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Los días de crédito deben estar entre 0 y 60."));
        mvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(61)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Los días de crédito deben estar entre 0 y 60."));
    }

    private static String body(int creditDays) {
        return "{\"name\":\"Cliente\",\"legacyCode\":\"HTTP1\",\"status\":\"active\",\"creditDays\":" + creditDays + "}";
    }
}
