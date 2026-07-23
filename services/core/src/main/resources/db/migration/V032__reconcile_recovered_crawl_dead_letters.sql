-- A transient upstream or DNS incident can create crawl dead letters after the
-- daily retention job has already run. If that endpoint has subsequently
-- succeeded, keep the failure record for audit but stop it blocking readiness.

update messaging.dead_letter_record d
set replay_status = 'IGNORED'
from source.source_endpoint e
where d.replay_status = 'PENDING'
  and d.event_type = 'source.crawl.requested'
  and e.id::text = d.payload #>> '{payload,endpointId}'
  and e.last_success_at > d.last_failed_at;
