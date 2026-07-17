import json

import pytest

from ai_hotspot_ai.connectors import accept_header, parse_connector
from ai_hotspot_ai.feed import FeedError


def test_sitemap_and_website_connectors_filter_and_normalize_links():
    sitemap = b"""<?xml version='1.0'?>
    <urlset xmlns='http://www.sitemaps.org/schemas/sitemap/0.9'>
      <url><loc>https://example.com/news/model-release</loc><lastmod>2026-07-17</lastmod></url>
      <url><loc>https://example.com/legal</loc></url>
    </urlset>"""
    entries = parse_connector(
        "SITEMAP", sitemap, "https://example.com/sitemap.xml", {"urlPattern": "/news/"}, 10
    )
    assert len(entries) == 1
    assert entries[0].original_url == "https://example.com/news/model-release"

    website = (
        b"<html><head><title>News</title></head><body>"
        b"<a href='/news/one'>Model One</a><a href='/about'>About</a></body></html>"
    )
    entries = parse_connector(
        "WEBSITE", website, "https://example.com/news", {"urlPattern": "/news/"}, 10
    )
    assert [entry.title for entry in entries] == ["Model One"]


@pytest.mark.parametrize(
    ("connector", "payload", "expected_title"),
    [
        (
            "GITHUB",
            [
                {
                    "id": 11,
                    "html_url": "https://github.com/org/repo/releases/tag/v1",
                    "tag_name": "v1",
                    "name": "Version 1",
                    "body": "Release notes",
                    "published_at": "2026-07-17T08:00:00Z",
                    "author": {"login": "maintainer"},
                }
            ],
            "Version 1",
        ),
        (
            "HUGGING_FACE",
            [
                {
                    "paper": {"id": "2607.00001", "title": "Useful Paper", "summary": "Abstract"},
                    "publishedAt": "2026-07-17T08:00:00Z",
                }
            ],
            "Useful Paper",
        ),
        (
            "OPENREVIEW",
            {
                "notes": [
                    {
                        "id": "note-1",
                        "forum": "forum-1",
                        "cdate": 1784275200000,
                        "content": {
                            "title": {"value": "Reviewed Paper"},
                            "abstract": {"value": "Abstract"},
                            "authors": {"value": ["A", "B"]},
                        },
                    }
                ]
            },
            "Reviewed Paper",
        ),
        (
            "HACKER_NEWS",
            {
                "hits": [
                    {
                        "objectID": "99",
                        "title": "AI system released",
                        "url": "https://example.com/ai",
                        "points": 20,
                        "author": "alice",
                        "created_at": "2026-07-17T08:00:00Z",
                    }
                ]
            },
            "AI system released",
        ),
    ],
)
def test_json_connectors_produce_unified_entries(connector, payload, expected_title):
    entries = parse_connector(
        connector, json.dumps(payload).encode(), "https://api.example.com/", {"minScore": 3}, 10
    )
    assert len(entries) == 1
    assert entries[0].title == expected_title
    assert len(entries[0].external_id) == 64


def test_reserved_x_connector_has_no_parser_or_network_contract():
    assert accept_header("X") == "text/html, application/xhtml+xml;q=0.9"
    with pytest.raises(FeedError, match="not available") as error:
        parse_connector("X", b"{}", "https://x.com/example", {}, 10)
    assert error.value.code == "CONNECTOR_NOT_AVAILABLE"
