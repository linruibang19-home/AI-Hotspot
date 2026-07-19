create table automation.subscription (
    id uuid primary key,
    user_id uuid not null references iam.user_account(id) on delete cascade,
    name text not null,
    frequency text not null,
    timezone text not null default 'Asia/Shanghai',
    send_time time not null default '09:00',
    weekday integer,
    topic_slugs text[] not null default '{}',
    source_levels text[] not null default '{OFFICIAL,FIRST_PARTY,THIRD_PARTY}',
    max_items integer not null default 12,
    status text not null default 'ACTIVE',
    next_run_at timestamptz not null,
    last_run_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_subscription_frequency check (frequency in ('DAILY','WEEKLY')),
    constraint ck_subscription_weekday check (weekday is null or weekday between 1 and 7),
    constraint ck_subscription_status check (status in ('ACTIVE','PAUSED','UNSUBSCRIBED')),
    constraint ck_subscription_max_items check (max_items between 1 and 50)
);
create index ix_subscription_due on automation.subscription(next_run_at,id) where status='ACTIVE';
create index ix_subscription_user on automation.subscription(user_id,created_at desc);

create table automation.subscription_run (
    id uuid primary key,
    subscription_id uuid not null references automation.subscription(id) on delete cascade,
    scheduled_at timestamptz not null,
    status text not null default 'RUNNING',
    item_count integer not null default 0,
    report_issue_id uuid references content.report_issue(id),
    started_at timestamptz not null default now(),
    completed_at timestamptz,
    error_message text,
    unique(subscription_id,scheduled_at),
    constraint ck_subscription_run_status check (status in ('RUNNING','SUCCEEDED','FAILED','SKIPPED'))
);

create table automation.email_delivery (
    id uuid primary key,
    subscription_run_id uuid not null references automation.subscription_run(id) on delete cascade,
    user_id uuid not null references iam.user_account(id) on delete cascade,
    recipient_email citext not null,
    provider_name text not null,
    subject text not null,
    status text not null default 'PENDING',
    attempt_count integer not null default 0,
    next_retry_at timestamptz,
    provider_message_id text,
    last_error text,
    sent_at timestamptz,
    created_at timestamptz not null default now(),
    constraint ck_delivery_status check (status in ('PENDING','SENDING','SENT','FAILED','CANCELLED')),
    constraint ck_delivery_attempt check (attempt_count between 0 and 10)
);
create index ix_email_delivery_retry on automation.email_delivery(next_retry_at,id) where status in ('PENDING','FAILED');

create table automation.unsubscribe_event (
    id uuid primary key,
    subscription_id uuid not null references automation.subscription(id) on delete cascade,
    user_id uuid not null references iam.user_account(id) on delete cascade,
    reason text,
    ip_hash text,
    created_at timestamptz not null default now()
);

insert into iam.permission(id,code,description) values
 (gen_random_uuid(),'subscription:manage','管理本人邮件订阅'),
 (gen_random_uuid(),'delivery:manage','查看和重试邮件投递')
on conflict(code) do nothing;
insert into iam.role_permission(role_id,permission_id)
select r.id,p.id from iam.role r join iam.permission p on
 (p.code='subscription:manage' and r.code in ('USER','EDITOR','OPERATOR','ADMIN')) or
 (p.code='delivery:manage' and r.code in ('OPERATOR','ADMIN'))
on conflict do nothing;
