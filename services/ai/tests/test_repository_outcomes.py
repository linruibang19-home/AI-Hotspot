from ai_hotspot_ai.repository import _failure_poll_outcome


def test_failure_poll_outcome_distinguishes_upstream_and_content_failures() -> None:
    assert _failure_poll_outcome("DNS_FAILURE") == "UPSTREAM_FAILURE"
    assert _failure_poll_outcome("NETWORK_FAILURE") == "UPSTREAM_FAILURE"
    assert _failure_poll_outcome("UPSTREAM_REJECTED") == "UPSTREAM_FAILURE"
    assert _failure_poll_outcome("UPSTREAM_TEMPORARY") == "UPSTREAM_FAILURE"
    assert _failure_poll_outcome("INVALID_REDIRECT") == "UPSTREAM_FAILURE"
    assert _failure_poll_outcome("TOO_MANY_REDIRECTS") == "UPSTREAM_FAILURE"
    assert _failure_poll_outcome("EMPTY_CONNECTOR_RESULT") == "CONTENT_FAILURE"
    assert _failure_poll_outcome("UNSAFE_XML") == "CONTENT_FAILURE"
