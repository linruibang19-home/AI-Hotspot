update source.source_endpoint
set status = 'ARCHIVED', next_fetch_at = null, updated_at = now()
where catalog_kind = 'TEST' and status <> 'ARCHIVED';

drop index source.ix_source_endpoint_due_fetch;
create index ix_source_endpoint_due_fetch
    on source.source_endpoint (next_fetch_at, id)
    where status = 'ACTIVE'
      and catalog_kind <> 'TEST'
      and endpoint_type in (
          'RSS', 'ATOM', 'WEBSITE', 'SITEMAP', 'GITHUB', 'HUGGING_FACE',
          'ARXIV', 'OPENREVIEW', 'HACKER_NEWS'
      );
