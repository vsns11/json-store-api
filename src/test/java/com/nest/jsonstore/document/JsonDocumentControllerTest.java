package com.nest.jsonstore.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nest.jsonstore.config.CorsProperties;
import com.nest.jsonstore.document.dto.JsonDocumentResponse;
import com.nest.jsonstore.error.DocumentNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JsonDocumentController.class)
@EnableConfigurationProperties(CorsProperties.class)
@EnableAutoConfiguration(exclude = org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class)
class JsonDocumentControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    JsonDocumentService service;

    @Test
    void createsDocument() throws Exception {
        UUID id = UUID.randomUUID();
        given(service.create(any())).willReturn(new JsonDocumentResponse(
                id, "Config", null, List.of("infra"), objectMapper.readTree("{\"a\":1}"), 7, 0,
                Instant.EPOCH, Instant.EPOCH));

        mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Config\",\"tags\":[\"infra\"],\"payload\":{\"a\":1}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.payload.a").value(1));
    }

    @Test
    void rejectsDocumentWithoutName() throws Exception {
        mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"a\":1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    }

    @Test
    void reportsWhereMalformedJsonBreaks() throws Exception {
        mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Broken\",\"payload\":{\"a\":}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid JSON"))
                .andExpect(jsonPath("$.location.line").value(1));
    }

    @Test
    void returnsNotFoundForUnknownId() throws Exception {
        UUID id = UUID.randomUUID();
        willThrow(new DocumentNotFoundException(id)).given(service).get(id);

        mockMvc.perform(get("/api/documents/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
