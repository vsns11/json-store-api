package com.nest.jsonstore.profile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {

    /**
     * One expression rather than four ORs, so the trigram index from V4 can serve it. Keep it
     * identical to the index definition, or PostgreSQL will fall back to scanning every row. The
     * list itself is read by {@link ProfileListings}, which leaves the inputs behind.
     */
    String SEARCH_PREDICATE = """
            where (cast(:search as text) is null
                or (p.name || ' ' || coalesce(p.description, '') || ' ' || p.tags::text || ' ' || p.payload::text)
                    ilike '%' || cast(:search as text) || '%')
              and (cast(:tag as text) is null
                or p.tags @> jsonb_build_array(cast(:tag as text)))
            """;

    @Query("select coalesce(sum(p.sizeBytes), 0) from Profile p")
    long totalBytes();

    @Query("select max(p.updatedAt) from Profile p")
    Instant lastUpdatedAt();
}
