-- Documents are test-scenario profiles: a named set of inputs that a scenario runs with.
-- The table is renamed to match; nothing about the columns changes.
alter table json_document rename to profile;

alter index json_document_pkey rename to profile_pkey;
alter index idx_json_document_updated_at rename to idx_profile_updated_at;
alter index idx_json_document_name rename to idx_profile_name;
alter index idx_json_document_payload rename to idx_profile_payload;

comment on table profile is 'Named input sets for test scenarios; the inputs themselves live in payload as jsonb.';
comment on column profile.name is 'What the scenario is called.';
comment on column profile.payload is 'The inputs the scenario runs with.';
