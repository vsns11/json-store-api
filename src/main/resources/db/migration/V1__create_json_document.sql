create table json_document (
    id          uuid         primary key,
    name        varchar(120) not null,
    description varchar(500),
    tags        jsonb        not null default '[]'::jsonb,
    payload     jsonb        not null,
    size_bytes  integer      not null,
    version     bigint       not null default 0,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);

comment on table json_document is 'User-supplied JSON documents, stored natively as jsonb.';

create index idx_json_document_updated_at on json_document (updated_at desc);
create index idx_json_document_name on json_document (lower(name));

-- Lets PostgreSQL answer containment queries (payload @> '{...}') straight from the index.
create index idx_json_document_payload on json_document using gin (payload jsonb_path_ops);
