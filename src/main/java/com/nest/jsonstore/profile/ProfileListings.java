package com.nest.jsonstore.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nest.jsonstore.profile.dto.ProfileSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One page of the profile list, read without the inputs or the template. Loading whole entities
 * pulled every profile's inputs — up to a megabyte each — across the wire just to show a name, a
 * size and a short preview; this asks PostgreSQL for exactly those.
 */
@Repository
class ProfileListings {

    private static final int PREVIEW_LENGTH = 180;

    /** Only these may appear in ORDER BY; ProfileService maps the API's sort keys onto them. */
    private static final Set<String> SORTABLE = Set.of("name", "created_at", "updated_at", "size_bytes");

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    /** Timestamps are written in UTC (hibernate.jdbc.time_zone), so they are read back the same way. */
    private static final Calendar UTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

    // The document names are sorted byte-wise ("C"), as Java sorts strings, so the order does not
    // depend on the database's collation. A row stored before V8 whose inputs are not an object has none.
    private static final String COLUMNS = """
            select p.id, p.name, p.description, p.tags::text as tags,
                   case when jsonb_typeof(p.payload) = 'object'
                        then (select coalesce(jsonb_agg(k order by k collate "C"), '[]'::jsonb)
                              from jsonb_object_keys(p.payload) as k)::text
                        else '[]' end as documents,
                   left(p.payload::text, %d) as preview,
                   p.size_bytes, p.version, p.updated_by, p.created_at, p.updated_at
            from profile p
            """.formatted(PREVIEW_LENGTH + 1);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    ProfileListings(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Page<ProfileSummary> page(String search, String tag, Pageable pageable) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("search", search)
                .addValue("tag", tag)
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());

        Long total = jdbc.queryForObject(
                "select count(*) from profile p " + ProfileRepository.SEARCH_PREDICATE, parameters, Long.class);
        List<ProfileSummary> rows = jdbc.query(
                COLUMNS + ProfileRepository.SEARCH_PREDICATE + orderBy(pageable.getSort()) + " limit :limit offset :offset",
                parameters,
                (row, index) -> summary(row));
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /**
     * The requested order, then the id. Without a column that is unique, rows that tie — two profiles
     * with the same name — may come back in a different order on the next page, so one is shown twice
     * and another never.
     */
    private static String orderBy(Sort sort) {
        String requested = sort.stream()
                .filter(order -> SORTABLE.contains(order.getProperty()))
                .map(order -> "p." + order.getProperty() + (order.isAscending() ? " asc" : " desc"))
                .collect(Collectors.joining(", "));
        return " order by " + (requested.isEmpty() ? "" : requested + ", ") + "p.id asc";
    }

    private ProfileSummary summary(ResultSet row) throws SQLException {
        String preview = row.getString("preview");
        if (preview.length() > PREVIEW_LENGTH) {
            preview = preview.substring(0, PREVIEW_LENGTH) + "…";
        }
        return new ProfileSummary(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getString("description"),
                strings(row.getString("tags")),
                strings(row.getString("documents")),
                preview,
                row.getInt("size_bytes"),
                row.getLong("version"),
                row.getString("updated_by"),
                instant(row, "created_at"),
                instant(row, "updated_at"));
    }

    private List<String> strings(String array) {
        try {
            return array == null ? List.of() : json.readValue(array, STRINGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("PostgreSQL returned a JSON array Jackson cannot read", e);
        }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column, (Calendar) UTC.clone());
        return value == null ? null : value.toInstant();
    }
}
