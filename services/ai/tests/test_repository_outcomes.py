import uuid

from ai_hotspot_ai.repository import (
    _failure_poll_outcome,
    _reconcile_endpoint_dead_letters,
)


def test_failure_poll_outcome_distinguishes_upstream_and_content_failures() -> None:
    assert _failure_poll_outcome("DNS_FAILURE") == "UPSTREAM_FAILURE"
    assert _failure_poll_outcome("NETWORK_FAILURE") == "UPSTREAM_FAILURE"
    assert _failure_poll_outcome("UPSTREAM_REJECTED") == "UPSTREAM_FAILURE"
    assert _failure_poll_outcome("UPSTREAM_TEMPORARY") == "UPSTREAM_FAILURE"
    assert _failure_poll_outcome("INVALID_REDIRECT") == "UPSTREAM_FAILURE"
    assert _failure_poll_outcome("TOO_MANY_REDIRECTS") == "UPSTREAM_FAILURE"
    assert _failure_poll_outcome("EMPTY_CONNECTOR_RESULT") == "CONTENT_FAILURE"
    assert _failure_poll_outcome("UNSAFE_XML") == "CONTENT_FAILURE"


def test_reconcile_endpoint_dead_letters_only_ignores_recovered_crawl_failures() -> None:
    endpoint_id = uuid.uuid4()

    class Cursor:
        def __init__(self) -> None:
            self.query = ""
            self.parameters: tuple[str] = ()

        def execute(self, query: str, parameters: tuple[str]) -> None:
            self.query = query
            self.parameters = parameters

    cursor = Cursor()
    _reconcile_endpoint_dead_letters(cursor, endpoint_id)  # type: ignore[arg-type]

    assert "replay_status = 'PENDING'" in cursor.query
    assert "event_type = 'source.crawl.requested'" in cursor.query
    assert "payload,endpointId" in cursor.query
    assert cursor.parameters == (str(endpoint_id),)
