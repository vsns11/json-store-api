-- Who wrote a profile, by directory username. A workspace sharing one store needs to know which
-- account changed a scenario, and the token already carries the name, so nothing new is asked of
-- the caller.
--
-- Both columns are nullable on purpose: every row that exists now was written before the API
-- recorded a name, and inventing one -- the migrator's account, or 'system' -- would be a lie
-- that reads exactly like a fact. The UI shows nothing at all for those.
alter table profile add column created_by varchar(120);
alter table profile add column updated_by varchar(120);

comment on column profile.created_by is 'Directory username that created this profile; null if it predates the column.';
comment on column profile.updated_by is 'Directory username that last changed this profile; null if it predates the column.';
