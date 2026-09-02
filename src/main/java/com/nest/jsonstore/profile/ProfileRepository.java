package com.nest.jsonstore.profile;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {

    String SEARCH_PREDICATE = """
            where cast(:search as text) is null
               or d.name ilike '%' || cast(:search as text) || '%'
               or coalesce(d.description, '') ilike '%' || cast(:search as text) || '%'
               or d.tags::text ilike '%' || cast(:search as text) || '%'
               or d.payload::text ilike '%' || cast(:search as text) || '%'
            """;

    /** Free-text search over the name, the description, the tags and the inputs themselves. */
    @Query(value = "select d.* from profile d " + SEARCH_PREDICATE,
            countQuery = "select count(*) from profile d " + SEARCH_PREDICATE,
            nativeQuery = true)
    Page<Profile> search(@Param("search") String search, Pageable pageable);

    @Query("select coalesce(sum(p.sizeBytes), 0) from Profile p")
    long totalBytes();

    @Query("select max(p.updatedAt) from Profile p")
    Instant lastUpdatedAt();
}
