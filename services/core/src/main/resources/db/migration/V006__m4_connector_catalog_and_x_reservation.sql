alter table source.source_endpoint
    add column catalog_kind text not null default 'USER_MANAGED',
    add column catalog_key text;

alter table source.source_endpoint
    add constraint ck_source_endpoint_catalog_kind
    check (catalog_kind in ('PRODUCTION', 'TEST', 'USER_MANAGED'));

create unique index uq_source_endpoint_catalog_key
    on source.source_endpoint (catalog_key)
    where catalog_key is not null;

create index ix_source_endpoint_catalog_status
    on source.source_endpoint (catalog_kind, status, health_status, updated_at desc);

update source.source_endpoint e
set catalog_kind = 'TEST'
from source.source_entity s
where s.id = e.source_entity_id
  and (
      s.name like 'Example M2 %'
      or s.name like 'UI M2 %'
      or s.name like '% M3 %'
      or e.url like '%m2-smoke=%'
      or e.url like '%m3-run=%'
  );

alter table source.source_endpoint drop constraint ck_source_endpoint_type;
alter table source.source_endpoint
    add constraint ck_source_endpoint_type
    check (endpoint_type in (
        'RSS', 'ATOM', 'WEBSITE', 'SITEMAP', 'GITHUB', 'HUGGING_FACE',
        'ARXIV', 'OPENREVIEW', 'HACKER_NEWS', 'PUBLIC_MEDIA',
        'PUBLIC_COMMUNITY', 'X'
    ));

alter table source.source_endpoint
    add constraint ck_source_endpoint_x_reserved
    check (endpoint_type <> 'X' or status in ('DRAFT', 'ARCHIVED'));

drop index source.ix_source_endpoint_due_fetch;
create index ix_source_endpoint_due_fetch
    on source.source_endpoint (next_fetch_at, id)
    where status = 'ACTIVE'
      and endpoint_type in (
          'RSS', 'ATOM', 'WEBSITE', 'SITEMAP', 'GITHUB', 'HUGGING_FACE',
          'ARXIV', 'OPENREVIEW', 'HACKER_NEWS'
      );
