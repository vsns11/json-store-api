package com.nest.jsonstore.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nest.jsonstore.config.CorsProperties;
import com.nest.jsonstore.profile.dto.ProfileResponse;
import com.nest.jsonstore.error.ProfileNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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

// A pure controller slice: authentication is covered by the integration test instead.
@WebMvcTest(ProfileController.class)
@AutoConfigureMockMvc(addFilters = false)
@EnableConfigurationProperties(CorsProperties.class)
@EnableAutoConfiguration(exclude = org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class)
class ProfileControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    ProfileService service;

    @Test
    void createsProfile() throws Exception {
        UUID id = UUID.randomUUID();
        given(service.create(any())).willReturn(new ProfileResponse(
                id, "Config", null, List.of("infra"), objectMapper.readTree("{\"a\":1}"), 7, 0,
                Instant.EPOCH, Instant.EPOCH));

        mockMvc.perform(post("/api/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Config\",\"tags\":[\"infra\"],\"payload\":{\"a\":1}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.payload.a").value(1));
    }

    @Test
    void rejectsProfileWithoutName() throws Exception {
        mockMvc.perform(post("/api/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"a\":1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    }

    @Test
    void reportsWhereMalformedJsonBreaks() throws Exception {
        mockMvc.perform(post("/api/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Broken\",\"payload\":{\"a\":}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid JSON"))
                .andExpect(jsonPath("$.location.line").value(1));
    }

    @Test
    void returnsNotFoundForUnknownId() throws Exception {
        UUID id = UUID.randomUUID();
        willThrow(new ProfileNotFoundException(id)).given(service).get(id);

        mockMvc.perform(get("/api/profiles/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
