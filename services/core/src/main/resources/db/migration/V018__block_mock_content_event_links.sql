-- Mock/Test 内容仅保留为私有审计记录，不允许重新进入主题事件聚合。
delete from content.content_event_relation member
using content.content_item item
where member.content_item_id = item.id
  and lower(coalesce(item.provider_name, '')) in ('mock','test','fixture');

create or replace function content.block_mock_content_event_link()
returns trigger language plpgsql as $$
begin
  if exists (
    select 1 from content.content_item item
    where item.id = new.content_item_id
      and lower(coalesce(item.provider_name, '')) in ('mock','test','fixture')
  ) then
    return null;
  end if;
  return new;
end $$;

drop trigger if exists trg_block_mock_content_event_link on content.content_event_relation;
create trigger trg_block_mock_content_event_link
before insert or update of content_item_id
on content.content_event_relation
for each row execute function content.block_mock_content_event_link();

delete from content.event_cluster cluster
where not exists (
  select 1 from content.content_event_relation member where member.event_cluster_id = cluster.id
)
and not exists (
  select 1 from governance.content_ticket ticket where ticket.event_cluster_id = cluster.id
);
