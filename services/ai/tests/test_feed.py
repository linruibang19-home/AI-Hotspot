from datetime import UTC

import pytest

from ai_hotspot_ai.feed import (
    FeedError,
    canonicalize_url,
    parse_feed,
    strip_markup,
    validate_public_url,
)
from ai_hotspot_ai.pipeline import _bounded_float, _bounded_int

RSS = b"""<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel><title>AI Feed</title><link>https://example.com/</link>
<item><guid>entry-1</guid><title>Model &amp; Tools</title>
<link>https://example.com/posts/1#section</link>
<description><![CDATA[<p>A useful <strong>AI</strong> update.</p>]]></description>
<pubDate>Thu, 16 Jul 2026 12:30:00 GMT</pubDate><author>editor@example.com</author>
</item></channel></rss>"""

ATOM = b"""<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom"><title>Research</title>
<entry><id>tag:example.com,2026:2</id><title>Agent Research</title>
<link href="/research/2"/><updated>2026-07-16T12:00:00Z</updated>
<summary>New agent evaluation.</summary></entry></feed>"""


@pytest.mark.parametrize("document", [RSS, ATOM])
def test_parse_rss_and_atom(document: bytes) -> None:
    entries = parse_feed(document, "https://example.com/feed.xml", 10)

    assert len(entries) == 1
    assert entries[0].title in {"Model & Tools", "Agent Research"}
    assert entries[0].external_id
    assert len(entries[0].entry_hash) == 64
    assert entries[0].published_at is not None
    assert entries[0].published_at.tzinfo == UTC
    assert entries[0].published_at_source in {"FEED_PUBLISHED", "FEED_UPDATED"}


def test_rss_html_is_plain_text_and_fragment_is_removed() -> None:
    entry = parse_feed(RSS, "https://example.com/feed.xml", 10)[0]

    assert entry.summary == "A useful AI update."
    assert entry.canonical_url == "https://example.com/posts/1"


def test_repeated_parse_has_stable_identity_and_hash() -> None:
    first = parse_feed(RSS, "https://example.com/feed.xml", 10)[0]
    second = parse_feed(RSS, "https://example.com/feed.xml", 10)[0]

    assert first.external_id == second.external_id
    assert first.entry_hash == second.entry_hash


def test_feed_recovers_explicit_date_from_url_when_feed_date_is_missing() -> None:
    feed = b"""<rss version="2.0"><channel><title>AI Feed</title><item>
    <guid>dated-url</guid><title>Release notes</title>
    <link>https://example.com/2026/06/29/release</link></item></channel></rss>"""
    entry = parse_feed(feed, "https://example.com/feed.xml", 10)[0]

    assert entry.published_at.isoformat() == "2026-06-29T00:00:00+00:00"
    assert entry.published_at_source == "URL_PATH"


def test_unsafe_xml_is_rejected_without_retry() -> None:
    with pytest.raises(FeedError) as raised:
        parse_feed(
            b'<!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><rss/>',
            "https://example.com/feed",
            10,
        )

    assert raised.value.code == "UNSAFE_XML"
    assert raised.value.retryable is False


@pytest.mark.parametrize(
    "address",
    ["127.0.0.1", "10.0.0.8", "169.254.169.254", "::1", "fc00::1"],
)
def test_ssrf_policy_rejects_non_public_dns_answers(
    monkeypatch: pytest.MonkeyPatch,
    address: str,
) -> None:
    monkeypatch.setattr(
        "ai_hotspot_ai.feed.socket.getaddrinfo",
        lambda *_args, **_kwargs: [(2, 1, 6, "", (address, 443))],
    )

    with pytest.raises(FeedError) as raised:
        validate_public_url("https://feed.example.com/rss")

    assert raised.value.code == "SSRF_BLOCKED"
    assert raised.value.retryable is False


def test_helpers_bound_untrusted_connector_config() -> None:
    assert _bounded_int("500", 50, 1, 200) == 200
    assert _bounded_int("bad", 50, 1, 200) == 50
    assert _bounded_float(120, 70) == 100
    assert canonicalize_url("/post#x", "https://EXAMPLE.com/feed") == "https://example.com/post"
    assert strip_markup("<p>Hello <b>AI</b></p>") == "Hello AI"
