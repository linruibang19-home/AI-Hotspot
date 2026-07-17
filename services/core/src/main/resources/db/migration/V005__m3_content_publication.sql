create table content.content_item (
    id uuid primary key,
    raw_entry_id uuid not null,
    source_entity_id uuid not null,
    endpoint_id uuid not null,
    original_url text,
    canonical_url text,
    original_title text not null,
    title_zh text,
    summary_zh text,
    recommendation_reason text,
    content_type text not null default 'ARTICLE',
    source_type text not null,
    source_official_level text not null,
    source_published_at timestamptz,
    publication_status text not null default 'CANDIDATE',
    visibility text not null default 'PRIVATE',
    admission_status text not null default 'PENDING',
    fact_status text not null default 'CONFIRMED',
    display_policy text not null,
    index_policy text not null,
    relevance_score numeric(5,2),
    quality_score numeric(5,2),
    final_score numeric(5,2),
    featured boolean not null default false,
    is_duplicate boolean not null default false,
    provider_name text,
    provider_model text,
    processing_version text,
    policy_snapshot jsonb not null default '{}'::jsonb,
    processed_at timestamptz,
    published_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_content_item_raw_entry unique (raw_entry_id),
    constraint fk_content_item__raw_entry foreign key (raw_entry_id) references source.raw_entry (id),
    constraint fk_content_item__source_entity foreign key (source_entity_id) references source.source_entity (id),
    constraint fk_content_item__endpoint foreign key (endpoint_id) references source.source_endpoint (id),
    constraint ck_content_item_type check (content_type in ('ARTICLE', 'RESEARCH', 'RELEASE', 'TUTORIAL', 'OTHER')),
    constraint ck_content_item_official_level check (source_official_level in ('OFFICIAL', 'FIRST_PARTY', 'THIRD_PARTY')),
    constraint ck_content_item_publication_status check (publication_status in ('CANDIDATE', 'PUBLISHED', 'UNPUBLISHED', 'REJECTED')),
    constraint ck_content_item_visibility check (visibility in ('PUBLIC', 'PRIVATE')),
    constraint ck_content_item_admission_status check (admission_status in ('PENDING', 'PASSED', 'FAILED', 'REVIEW_REQUIRED')),
    constraint ck_content_item_fact_status check (fact_status in ('CONFIRMED', 'UNCONFIRMED', 'DEBUNKED')),
    constraint ck_content_item_display_policy check (display_policy in ('FULLTEXT_ALLOWED', 'SUMMARY_ONLY', 'LINK_ONLY', 'HIDDEN')),
    constraint ck_content_item_index_policy check (index_policy in ('PUBLIC_RAG', 'PRIVATE_RAG', 'METADATA_ONLY', 'NO_INDEX')),
    constraint ck_content_item_relevance_score check (relevance_score is null or relevance_score between 0 and 100),
    constraint ck_content_item_quality_score check (quality_score is null or quality_score between 0 and 100),
    constraint ck_content_item_final_score check (final_score is null or final_score between 0 and 100)
);

create index ix_content_item_endpoint_created on content.content_item (endpoint_id, created_at desc, id desc);
create index ix_content_item_source_entity on content.content_item (source_entity_id, created_at desc, id desc);
create index ix_content_item_public_feed
    on content.content_item (published_at desc, id desc)
    where publication_status = 'PUBLISHED'
      and visibility = 'PUBLIC'
      and admission_status = 'PASSED'
      and is_duplicate = false
      and fact_status <> 'DEBUNKED';

create index ix_content_item_featured_feed
    on content.content_item (final_score desc, published_at desc, id desc)
    where publication_status = 'PUBLISHED'
      and visibility = 'PUBLIC'
      and admission_status = 'PASSED'
      and featured = true
      and is_duplicate = false
      and fact_status <> 'DEBUNKED';

create index ix_content_item_pending_processing
    on content.content_item (created_at, id)
    where admission_status = 'PENDING';
