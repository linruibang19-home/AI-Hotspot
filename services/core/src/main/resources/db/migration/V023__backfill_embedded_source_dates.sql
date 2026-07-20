-- Recover explicit publication dates embedded in WEBSITE listing text.
-- Only unambiguous English long dates are backfilled; unknown dates remain visible to monitoring.
with candidates as (
    select c.id content_id, c.raw_entry_id,
           to_date((m.parts)[1] || ' ' || (m.parts)[2] || ' ' || (m.parts)[3], 'Month DD YYYY')::timestamptz recovered_at
    from content.content_item c
    cross join lateral regexp_match(c.original_title,
      '(January|February|March|April|May|June|July|August|September|October|November|December) ([0-9]{1,2}), ([0-9]{4})',
      'i') m(parts)
    where c.source_published_at is null
), valid as (
    select * from candidates where recovered_at <= now() + interval '1 day'
), updated_content as (
    update content.content_item c set source_published_at=v.recovered_at, updated_at=now()
    from valid v where c.id=v.content_id returning c.id,c.raw_entry_id,c.source_published_at
), updated_raw as (
    update source.raw_entry r set source_published_at=u.source_published_at, updated_at=now()
    from updated_content u where r.id=u.raw_entry_id returning r.id
)
update knowledge.chunk ch
set source_published_at=c.source_published_at,
    effective_published_at=c.source_published_at
from knowledge.document d join content.content_item c on c.id=d.content_item_id
where ch.document_id=d.id and c.id in(select content_id from valid);
