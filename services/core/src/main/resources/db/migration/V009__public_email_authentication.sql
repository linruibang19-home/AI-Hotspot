create table iam.email_verification_challenge (
    id uuid primary key,
    email citext not null,
    purpose text not null,
    code_hash text not null,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    failed_attempts integer not null default 0,
    max_attempts integer not null default 5,
    requested_ip_hash text,
    created_at timestamptz not null default now(),
    constraint ck_email_challenge_purpose check (purpose in ('REGISTER', 'LOGIN')),
    constraint ck_email_challenge_attempts check (
        failed_attempts >= 0 and max_attempts between 1 and 10 and failed_attempts <= max_attempts
    )
);

create index ix_email_challenge_lookup
    on iam.email_verification_challenge (email, purpose, created_at desc);

create index ix_email_challenge_expiry
    on iam.email_verification_challenge (expires_at)
    where consumed_at is null;

comment on table iam.email_verification_challenge is
    '邮箱注册与免密登录验证码挑战；只保存带服务端 pepper 的摘要，不保存明文验证码';

