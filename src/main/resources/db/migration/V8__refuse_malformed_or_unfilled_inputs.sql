-- The server now composes every profile's inputs itself, so nothing it writes can contain an unfilled
-- placeholder or be anything but a set of named documents. These constraints make that true of the
-- database too, whatever writes to it: a seeder that goes straight to the repository, a script, a
-- hand-run INSERT. One seeded profile stored the unfilled sku placeholder as literal text precisely
-- because the only guard was in application code that one path skipped.
--
-- NOT VALID: the constraints apply to every insert and update from now on, but existing rows are not
-- scanned, so a database that already holds a bad row still migrates. Repair those rows with
--   POST /api/admin/profiles/recompose?apply=true
-- and then, once the dry run reports nothing left to change, make the constraints cover old rows too:
--   alter table profile validate constraint profile_payload_is_named_documents;
--   alter table profile validate constraint profile_payload_has_no_placeholder;
--
-- The placeholder marker is built from two literals, and never written whole in this file, because
-- Flyway substitutes its own placeholders everywhere in a migration, comments included.

alter table profile
    add constraint profile_payload_is_named_documents
        check (jsonb_typeof(payload) = 'object') not valid;

alter table profile
    add constraint profile_payload_has_no_placeholder
        check (strpos(payload::text, '$' || '{') = 0) not valid;
