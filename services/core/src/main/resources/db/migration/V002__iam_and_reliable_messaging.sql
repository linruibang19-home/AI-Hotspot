create table iam.user_account (
    id uuid primary key,
    email citext not null,
    display_name text not null,
    password_hash text,
    status text not null,
    locale text not null default 'zh-CN',
    timezone text not null default 'Asia/Shanghai',
    version bigint not null default 0,
    created_by uuid,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_login_at timestamptz,
    constraint uq_user_account_email unique (email),
    constraint ck_user_account_status check (status in ('INVITED', 'ACTIVE', 'SUSPENDED', 'DELETED')),
    constraint fk_user_account_created_by foreign key (created_by) references iam.user_account (id)
);

create index ix_user_account_created_by on iam.user_account (created_by)
where created_by is not null;

create table iam.invitation_code (
    id uuid primary key,
    code_hash text not null,
    created_by uuid not null,
    default_role text not null default 'USER',
    max_uses integer not null default 1,
    used_count integer not null default 0,
    expires_at timestamptz not null,
    status text not null default 'ACTIVE',
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    constraint uq_invitation_code_hash unique (code_hash),
    constraint ck_invitation_code_usage check (max_uses > 0 and used_count >= 0 and used_count <= max_uses),
    constraint ck_invitation_code_status check (status in ('ACTIVE', 'EXHAUSTED', 'EXPIRED', 'REVOKED')),
    constraint fk_invitation_code_created_by foreign key (created_by) references iam.user_account (id)
);

create index ix_invitation_code_created_by on iam.invitation_code (created_by);
create index ix_invitation_code_active_expiry on iam.invitation_code (expires_at)
where status = 'ACTIVE';

create table messaging.outbox_event (
    id uuid primary key,
    event_id uuid not null,
    event_type text not null,
    event_version integer not null,
    aggregate_type text not null,
    aggregate_id uuid not null,
    payload jsonb not null,
    status text not null default 'PENDING',
    retry_count integer not null default 0,
    next_retry_at timestamptz,
    created_at timestamptz not null default now(),
    published_at timestamptz,
    last_error text,
    constraint uq_outbox_event_event_id unique (event_id),
    constraint ck_outbox_event_version check (event_version > 0),
    constraint ck_outbox_event_retry_count check (retry_count >= 0),
    constraint ck_outbox_event_status check (status in ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED'))
);

create index ix_outbox_event_pending on messaging.outbox_event (next_retry_at, created_at)
where status in ('PENDING', 'FAILED');
create index ix_outbox_event_aggregate on messaging.outbox_event (aggregate_type, aggregate_id, created_at desc);

create table messaging.consumer_inbox (
    event_id uuid not null,
    idempotency_key text not null,
    consumer_name text not null,
    status text not null,
    processed_at timestamptz,
    result jsonb,
    last_error text,
    created_at timestamptz not null default now(),
    primary key (event_id, consumer_name),
    constraint uq_consumer_inbox_idempotency unique (idempotency_key, consumer_name),
    constraint ck_consumer_inbox_status check (status in ('PROCESSING', 'SUCCEEDED', 'FAILED'))
);

create index ix_consumer_inbox_failed on messaging.consumer_inbox (created_at)
where status = 'FAILED';

create table messaging.dead_letter_record (
    id uuid primary key,
    original_event_id uuid not null,
    queue_name text not null,
    event_type text not null,
    payload jsonb not null,
    failure_count integer not null,
    first_failed_at timestamptz not null,
    last_failed_at timestamptz not null,
    last_error text not null,
    replay_status text not null default 'PENDING',
    replayed_by uuid,
    replay_event_id uuid,
    created_at timestamptz not null default now(),
    constraint ck_dead_letter_failure_count check (failure_count > 0),
    constraint ck_dead_letter_replay_status check (replay_status in ('PENDING', 'REPLAYED', 'IGNORED')),
    constraint fk_dead_letter_replayed_by foreign key (replayed_by) references iam.user_account (id)
);

create index ix_dead_letter_record_queue_status on messaging.dead_letter_record (queue_name, replay_status, last_failed_at desc);
create index ix_dead_letter_record_replayed_by on messaging.dead_letter_record (replayed_by)
where replayed_by is not null;
