alter table research.citation
  add column if not exists source_title text,
  add column if not exists source_name text,
  add column if not exists source_url text,
  add column if not exists source_published_at timestamptz,
  add column if not exists provenance_status text not null default 'LEGACY_MISSING';

update research.citation c
set source_title=d.title,
    source_name=se.name,
    source_url=ch.source_url,
    source_published_at=ch.effective_published_at,
    provenance_status='VERIFIED'
from knowledge.chunk ch
join knowledge.document d on d.id=ch.document_id
join source.source_entity se on se.id=ch.source_entity_id
where ch.id=c.chunk_id
  and nullif(trim(ch.source_url),'') is not null;

update research.citation c
set provenance_status='LEGACY_MISSING'
where not exists (
  select 1
  from knowledge.chunk ch
  join knowledge.document d on d.id=ch.document_id
  join source.source_entity se on se.id=ch.source_entity_id
  where ch.id=c.chunk_id and nullif(trim(ch.source_url),'') is not null
);

alter table research.citation
  drop constraint if exists ck_citation_provenance_status,
  add constraint ck_citation_provenance_status
    check (provenance_status in ('VERIFIED','LEGACY_MISSING'));

alter table research.citation
  drop constraint if exists ck_citation_verified_snapshot,
  add constraint ck_citation_verified_snapshot check (
    provenance_status<>'VERIFIED' or (
      nullif(trim(source_title),'') is not null
      and nullif(trim(source_name),'') is not null
      and source_url ~ '^https?://'
    )
  );

create index if not exists ix_citation_provenance_status
  on research.citation(provenance_status, created_at desc);

comment on column research.citation.provenance_status is
  'VERIFIED 表示来源字段在查询时从真实知识库固化；LEGACY_MISSING 表示旧 Chunk 已删除且禁止猜测补源。';
comment on column research.citation.source_url is
  '查询发生时从 knowledge.chunk 固化的原始 URL，不接受 Generation 模型输出。';
