package com.nest.jsonstore.document;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real stack against a real PostgreSQL: Flyway migrations, the jsonb mapping,
 * payload-inclusive search and the size limit. Requires a working Docker daemon.
 */
@SpringBootTest(properties = {"app.seed-examples=false", "app.limits.max-payload-bytes=400"})
@AutoConfigureMockMvc
@Testcontainers
class JsonDocumentIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void storesThePayloadAsRealJsonbAndFindsItByItsContents() throws Exception {
        String id = mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Topology","tags":["infra"],
                                 "payload":{"services":[{"name":"api","region":"eu-west"}],"active":true}}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sizeBytes").value(62))
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // Stored as a jsonb object, so PostgreSQL can read inside it.
        String type = jdbcTemplate.queryForObject(
                "select jsonb_typeof(payload) from json_document where id = ?::uuid", String.class, id);
        String region = jdbcTemplate.queryForObject(
                "select payload -> 'services' -> 0 ->> 'region' from json_document where id = ?::uuid", String.class, id);
        assertThat(type).isEqualTo("object");
        assertThat(region).isEqualTo("eu-west");

        // Search reaches into the payload, not just the name.
        mockMvc.perform(get("/api/documents").param("search", "eu-west"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Topology"));

        mockMvc.perform(delete("/api/documents/{id}", id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/documents/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsPayloadsOverTheConfiguredLimit() throws Exception {
        String oversized = "{\"blob\":\"" + "x".repeat(500) + "\"}";

        mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Too big\",\"payload\":%s}".formatted(oversized)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("Payload too large"));
    }

    @Test
    void rejectsAnIdThatIsNotAUuid() throws Exception {
        mockMvc.perform(get("/api/documents/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
