-- Filtering by tag is an exact containment test, which a GIN index on the tags array answers
-- directly instead of reading every row.
create index if not exists idx_profile_tags on profile using gin (tags jsonb_path_ops);
