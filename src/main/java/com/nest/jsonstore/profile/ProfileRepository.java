package com.nest.jsonstore.profile;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {

    /**
     * One expression rather than four ORs, so the trigram index from V4 can serve it. Keep it
     * identical to the index definition, or PostgreSQL will fall back to scanning every row.
     */
    String SEARCH_PREDICATE = """
            where (cast(:search as text) is null
                or (p.name || ' ' || coalesce(p.description, '') || ' ' || p.tags::text || ' ' || p.payload::text)
                    ilike '%' || cast(:search as text) || '%')
              and (cast(:tag as text) is null
                or p.tags @> jsonb_build_array(cast(:tag as text)))
            """;

    /**
     * Free-text search over the name, the description, the tags and the inputs themselves,
     * optionally narrowed to one tag.
     */
    @Query(value = "select p.* from profile p " + SEARCH_PREDICATE,
            countQuery = "select count(*) from profile p " + SEARCH_PREDICATE,
            nativeQuery = true)
    Page<Profile> search(@Param("search") String search, @Param("tag") String tag, Pageable pageable);

    @Query("select coalesce(sum(p.sizeBytes), 0) from Profile p")
    long totalBytes();

    @Query("select max(p.updatedAt) from Profile p")
    Instant lastUpdatedAt();
}
