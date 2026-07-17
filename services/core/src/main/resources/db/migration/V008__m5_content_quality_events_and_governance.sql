alter table content.content_item
    add column category_code text,
    add column language_code text,
    add column confidence_score numeric(5,2),
    add column duplicate_of_id uuid,
    add column duplicate_similarity numeric(6,5),
    add column quality_dimensions jsonb not null default '{}'::jsonb,
    add column generation_metadata jsonb not null default '{}'::jsonb,
    add column version bigint not null default 0;

alter table content.content_item
    add constraint fk_content_item__duplicate_of foreign key (duplicate_of_id) references content.content_item (id),
    add constraint ck_content_item_confidence check (confidence_score is null or confidence_score between 0 and 100),
    add constraint ck_content_item_duplicate_similarity check (duplicate_similarity is null or duplicate_similarity between 0 and 1),
    add constraint ck_content_item_duplicate_reference check (
        (is_duplicate = false and duplicate_of_id is null)
        or (is_duplicate = true and duplicate_of_id is not null and duplicate_of_id <> id)
    );

create index ix_content_item_duplicate_of on content.content_item (duplicate_of_id) where duplicate_of_id is not null;
create index ix_content_item_review_queue on content.content_item (admission_status, updated_at desc, id desc)
where admission_status in ('PENDING', 'REVIEW_REQUIRED') or fact_status in ('UNCONFIRMED', 'DEBUNKED');

create table content.content_tag (
    content_item_id uuid not null,
    tag text not null,
    source text not null default 'AI',
    confidence numeric(5,2) not null default 100,
    created_at timestamptz not null default now(),
    primary key (content_item_id, tag),
    constraint fk_content_tag__content foreign key (content_item_id) references content.content_item (id) on delete cascade,
    constraint ck_content_tag_source check (source in ('AI', 'RULE', 'EDITOR')),
    constraint ck_content_tag_confidence check (confidence between 0 and 100)
);

create index ix_content_tag_tag on content.content_tag (tag, content_item_id);

create table content.content_entity (
    id uuid primary key,
    content_item_id uuid not null,
    entity_type text not null,
    canonical_name text not null,
    display_name text not null,
    confidence numeric(5,2) not null default 100,
    created_at timestamptz not null default now(),
    constraint fk_content_entity__content foreign key (content_item_id) references content.content_item (id) on delete cascade,
    constraint ck_content_entity_type check (entity_type in ('COMPANY', 'MODEL', 'PRODUCT', 'PERSON', 'TECHNOLOGY', 'ORGANIZATION', 'OTHER')),
    constraint ck_content_entity_confidence check (confidence between 0 and 100),
    constraint uq_content_entity unique (content_item_id, entity_type, canonical_name)
);

create index ix_content_entity_name on content.content_entity (canonical_name, entity_type, content_item_id);

create table content.event_cluster (
    id uuid primary key,
    cluster_key text not null,
    title text not null,
    summary text,
    category_code text,
    status text not null default 'ACTIVE',
    fact_status text not null default 'CONFIRMED',
    primary_content_id uuid,
    first_seen_at timestamptz not null,
    last_seen_at timestamptz not null,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_event_cluster_key unique (cluster_key),
    constraint fk_event_cluster__primary_content foreign key (primary_content_id) references content.content_item (id),
    constraint ck_event_cluster_status check (status in ('ACTIVE', 'MERGED', 'ARCHIVED')),
    constraint ck_event_cluster_fact_status check (fact_status in ('CONFIRMED', 'UNCONFIRMED', 'DEBUNKED'))
);

create index ix_event_cluster_recent on content.event_cluster (status, last_seen_at desc, id desc);

create table content.content_event_relation (
    content_item_id uuid not null,
    event_cluster_id uuid not null,
    relation_type text not null default 'RELATED',
    confidence numeric(5,2) not null default 100,
    is_primary boolean not null default false,
    created_by uuid,
    created_at timestamptz not null default now(),
    primary key (content_item_id, event_cluster_id),
    constraint fk_content_event_relation__content foreign key (content_item_id) references content.content_item (id) on delete cascade,
    constraint fk_content_event_relation__event foreign key (event_cluster_id) references content.event_cluster (id) on delete cascade,
    constraint fk_content_event_relation__creator foreign key (created_by) references iam.user_account (id),
    constraint ck_content_event_relation_type check (relation_type in ('PRIMARY', 'RELATED', 'FOLLOW_UP', 'CONTRADICTS')),
    constraint ck_content_event_relation_confidence check (confidence between 0 and 100)
);

create unique index uq_content_event_primary on content.content_event_relation (content_item_id) where is_primary;
create index ix_content_event_relation_event on content.content_event_relation (event_cluster_id, created_at desc);

create table content.model_run (
    id uuid primary key,
    content_item_id uuid not null,
    provider_name text not null,
    provider_model text not null,
    prompt_version text not null,
    status text not null,
    input_fingerprint text not null,
    output_data jsonb,
    error_code text,
    latency_ms bigint,
    created_at timestamptz not null default now(),
    constraint fk_model_run__content foreign key (content_item_id) references content.content_item (id) on delete cascade,
    constraint ck_model_run_status check (status in ('SUCCEEDED', 'FAILED'))
);

create index ix_model_run_content on content.model_run (content_item_id, created_at desc);

create table governance.content_ticket (
    id uuid primary key,
    content_item_id uuid,
    event_cluster_id uuid,
    ticket_type text not null,
    status text not null default 'OPEN',
    priority text not null default 'NORMAL',
    reason text not null,
    contact_email text,
    resolution text,
    submitted_by uuid,
    assigned_to uuid,
    resolved_by uuid,
    created_at timestamptz not null default now(),
    resolved_at timestamptz,
    updated_at timestamptz not null default now(),
    constraint fk_content_ticket__content foreign key (content_item_id) references content.content_item (id),
    constraint fk_content_ticket__event foreign key (event_cluster_id) references content.event_cluster (id),
    constraint fk_content_ticket__submitter foreign key (submitted_by) references iam.user_account (id),
    constraint fk_content_ticket__assignee foreign key (assigned_to) references iam.user_account (id),
    constraint fk_content_ticket__resolver foreign key (resolved_by) references iam.user_account (id),
    constraint ck_content_ticket_target check (content_item_id is not null or event_cluster_id is not null),
    constraint ck_content_ticket_type check (ticket_type in ('REVIEW', 'CORRECTION', 'TAKEDOWN', 'COPYRIGHT')),
    constraint ck_content_ticket_status check (status in ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'REJECTED')),
    constraint ck_content_ticket_priority check (priority in ('LOW', 'NORMAL', 'HIGH', 'URGENT'))
);

create index ix_content_ticket_queue on governance.content_ticket (status, priority, created_at, id);
create index ix_content_ticket_content on governance.content_ticket (content_item_id, created_at desc) where content_item_id is not null;

insert into iam.permission (code, description) values
    ('content:read', '读取内容审核与事件数据'),
    ('content:review', '审核、纠错、下架与恢复内容'),
    ('event:manage', '维护内容与事件的跨事件关联'),
    ('ticket:manage', '处理纠错和下架工单')
on conflict (code) do nothing;

insert into iam.role_permission (role_id, permission_id)
select r.id, p.id
from iam.role r
join iam.permission p on
    (r.code in ('EDITOR', 'OPERATOR', 'ADMIN') and p.code = 'content:read')
    or (r.code in ('OPERATOR', 'ADMIN') and p.code in ('content:review', 'event:manage', 'ticket:manage'))
on conflict do nothing;
