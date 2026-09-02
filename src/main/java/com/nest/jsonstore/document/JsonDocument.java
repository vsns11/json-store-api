package com.nest.jsonstore.document;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A single JSON document, stored in a native PostgreSQL {@code jsonb} column.
 */
@Entity
@Table(name = "json_document")
public class JsonDocument {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> tags = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected JsonDocument() {
        // for JPA
    }

    public JsonDocument(String name, String description, List<String> tags, JsonNode payload, int sizeBytes) {
        apply(name, description, tags, payload, sizeBytes);
    }

    /** Applies the mutable part of the document in one shot, so callers cannot leave it half-updated. */
    public void apply(String name, String description, List<String> tags, JsonNode payload, int sizeBytes) {
        this.name = name;
        this.description = description;
        this.tags = tags == null ? List.of() : List.copyOf(tags);
        this.payload = payload;
        this.sizeBytes = sizeBytes;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getTags() {
        return tags;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
