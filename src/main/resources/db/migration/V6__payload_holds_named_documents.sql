-- A scenario often feeds more than one system, and each system wants its own document. The inputs
-- are therefore a set of named documents rather than a single one:
--
--   {"orders-api": {...}, "billing": {...}}
--
-- Everything stored so far was a single document, so it becomes the one named "main".
update profile set payload = jsonb_build_object('main', payload);

comment on column profile.payload is
    'Named input documents, one per system the scenario feeds. Always an object; never empty.';
