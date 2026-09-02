-- How a profile was composed, for the ones built from the template catalogue: which fragment was
-- chosen in each group, and what was typed into their fields. Null for profiles written by hand.
alter table profile add column template jsonb;

comment on column profile.template is
    'Template selection and field values a composed profile was built from; null if it was written by hand.';
