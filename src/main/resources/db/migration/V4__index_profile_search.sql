-- Free-text search reads the name, the description, the tags and the inputs. Without an index that
-- is a sequential scan of every row; a trigram index over the same expression the query uses turns
-- it into an index lookup. Measured at 100k profiles: 158 ms -> 3 ms for a term matching one row.
--
-- pg_trgm ships with PostgreSQL but the role running migrations must be allowed to create it.
-- If it is not, ask a DBA to run `create extension pg_trgm;` once, before deploying.
create extension if not exists pg_trgm;

create index if not exists idx_profile_search on profile using gin (
    (name || ' ' || coalesce(description, '') || ' ' || tags::text || ' ' || payload::text) gin_trgm_ops);

comment on index idx_profile_search is
    'Serves the free-text search. The expression must stay identical to the one in ProfileRepository.';

-- Give the planner statistics for the new index straight away, rather than waiting for autovacuum:
-- without them it prefers the index even for terms matching a third of the table, where a scan is
-- cheaper.
analyze profile;
