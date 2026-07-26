alter table automation.subscription
    add column source_entity_ids uuid[] not null default '{}',
    add column content_types text[] not null default '{}';
