create table research.answer_feedback (
    id uuid primary key,
    query_run_id uuid not null unique references research.query_run(id) on delete cascade,
    session_id uuid not null references research.session(id) on delete cascade,
    user_id uuid not null references iam.user_account(id) on delete cascade,
    rating text not null,
    issue_categories text[] not null default '{}',
    comment text,
    triage_status text not null default 'NEW',
    triaged_by uuid references iam.user_account(id),
    triaged_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_answer_feedback_rating check (rating in ('HELPFUL','UNHELPFUL')),
    constraint ck_answer_feedback_triage check (
        triage_status in ('NEW','TRIAGED','EVAL_CANDIDATE','RESOLVED','DISMISSED')
    ),
    constraint ck_answer_feedback_comment check (comment is null or char_length(comment) <= 1000),
    constraint ck_answer_feedback_unhelpful_reason check (
        rating = 'HELPFUL' or cardinality(issue_categories) > 0
    )
);

create index ix_answer_feedback_triage_recent
    on research.answer_feedback(triage_status, updated_at desc);
create index ix_answer_feedback_user_recent
    on research.answer_feedback(user_id, updated_at desc);

comment on table research.answer_feedback is 'RAG answer-level user feedback and curated failure-sample workflow';
comment on column research.answer_feedback.issue_categories is 'Controlled failure taxonomy used by RAG stage-B experiments';
comment on column research.answer_feedback.triage_status is 'NEW, TRIAGED, EVAL_CANDIDATE, RESOLVED or DISMISSED';
