package com.nest.jsonstore.document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface JsonDocumentRepository extends JpaRepository<JsonDocument, UUID> {

    String SEARCH_PREDICATE = """
            where cast(:search as text) is null
               or d.name ilike '%' || cast(:search as text) || '%'
               or coalesce(d.description, '') ilike '%' || cast(:search as text) || '%'
               or d.tags::text ilike '%' || cast(:search as text) || '%'
               or d.payload::text ilike '%' || cast(:search as text) || '%'
            """;

    /** Free-text search over name, description, tags and the JSON payload itself. */
    @Query(value = "select d.* from json_document d " + SEARCH_PREDICATE,
            countQuery = "select count(*) from json_document d " + SEARCH_PREDICATE,
            nativeQuery = true)
    Page<JsonDocument> search(@Param("search") String search, Pageable pageable);

    @Query("select coalesce(sum(d.sizeBytes), 0) from JsonDocument d")
    long totalBytes();

    @Query("select max(d.updatedAt) from JsonDocument d")
    Instant lastUpdatedAt();
}
