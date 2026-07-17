import hashlib
import json
import re
from datetime import UTC, datetime
from html.parser import HTMLParser
from urllib.parse import urlsplit
from xml.etree import ElementTree

from ai_hotspot_ai.feed import (
    UNSAFE_XML_PATTERN,
    FeedEntry,
    FeedError,
    canonicalize_url,
    parse_feed,
    strip_markup,
)

FEED_TYPES = {"RSS", "ATOM", "ARXIV"}


def accept_header(endpoint_type: str) -> str:
    if endpoint_type in FEED_TYPES or endpoint_type == "SITEMAP":
        return "application/atom+xml, application/rss+xml, application/xml, text/xml;q=0.9"
    if endpoint_type in {"GITHUB", "HUGGING_FACE", "OPENREVIEW", "HACKER_NEWS"}:
        return "application/json"
    return "text/html, application/xhtml+xml;q=0.9"


def parse_connector(
    endpoint_type: str, content: bytes, base_url: str, config: dict[str, object], max_items: int
) -> list[FeedEntry]:
    if endpoint_type in FEED_TYPES:
        return parse_feed(content, base_url, max_items)
    parsers = {
        "SITEMAP": lambda: _parse_sitemap(content, base_url, config, max_items),
        "WEBSITE": lambda: _parse_website(content, base_url, config, max_items),
        "GITHUB": lambda: _parse_github(content, base_url, max_items),
        "HUGGING_FACE": lambda: _parse_hugging_face(content, base_url, max_items),
        "OPENREVIEW": lambda: _parse_openreview(content, base_url, max_items),
        "HACKER_NEWS": lambda: _parse_hacker_news(content, base_url, config, max_items),
    }
    parser = parsers.get(endpoint_type)
    if not parser:
        raise FeedError(
            "CONNECTOR_NOT_AVAILABLE",
            f"Connector {endpoint_type} is not available",
            retryable=False,
        )
    return parser()


def _parse_sitemap(
    content: bytes, base_url: str, config: dict[str, object], max_items: int
) -> list[FeedEntry]:
    if UNSAFE_XML_PATTERN.search(content[:65536]):
        raise FeedError(
            "UNSAFE_XML", "DTD and entity declarations are not accepted", retryable=False
        )
    try:
        root = ElementTree.fromstring(content)
    except ElementTree.ParseError as exception:
        raise FeedError("INVALID_SITEMAP", str(exception), retryable=False) from exception
    pattern = _pattern(config)
    entries: list[FeedEntry] = []
    for node in root.iter():
        if not node.tag.endswith("url"):
            continue
        values = {child.tag.rsplit("}", 1)[-1]: (child.text or "").strip() for child in node}
        url = canonicalize_url(values.get("loc"), base_url)
        if not url or (pattern and not pattern.search(url)):
            continue
        published_at = _parse_datetime(values.get("lastmod"))
        path_name = urlsplit(url).path.rstrip("/").rsplit("/", 1)[-1]
        title = (
            re.sub(r"[-_]+", " ", path_name).strip() or urlsplit(url).hostname or "Sitemap entry"
        )
        entries.append(_entry(url, url, title, None, published_at, None, {"loc": url, **values}))
        if len(entries) >= max_items:
            break
    return _require_entries(entries, "Sitemap contains no usable URLs")


class _WebsiteParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.page_title: list[str] = []
        self.in_title = False
        self.current_href: str | None = None
        self.current_text: list[str] = []
        self.links: list[tuple[str, str]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "title":
            self.in_title = True
        if tag == "a":
            self.current_href = dict(attrs).get("href")
            self.current_text = []

    def handle_endtag(self, tag: str) -> None:
        if tag == "title":
            self.in_title = False
        if tag == "a" and self.current_href:
            self.links.append((self.current_href, " ".join("".join(self.current_text).split())))
            self.current_href = None
            self.current_text = []

    def handle_data(self, data: str) -> None:
        if self.in_title:
            self.page_title.append(data)
        if self.current_href is not None:
            self.current_text.append(data)


def _parse_website(
    content: bytes, base_url: str, config: dict[str, object], max_items: int
) -> list[FeedEntry]:
    parser = _WebsiteParser()
    parser.feed(content.decode("utf-8", errors="replace"))
    pattern = _pattern(config)
    base_host = urlsplit(base_url).hostname
    entries: list[FeedEntry] = []
    seen: set[str] = set()
    for href, text in parser.links:
        url = canonicalize_url(href, base_url)
        if (
            not url
            or url in seen
            or urlsplit(url).hostname != base_host
            or (pattern and not pattern.search(url))
        ):
            continue
        title = strip_markup(text, 600)
        if not title or len(title) < 4:
            continue
        seen.add(url)
        entries.append(_entry(url, url, title, None, None, None, {"url": url, "title": title}))
        if len(entries) >= max_items:
            break
    if not entries:
        title = strip_markup(" ".join(parser.page_title), 600) or base_host or "Website"
        entries.append(_entry(base_url, base_url, title, None, None, None, {"url": base_url}))
    return entries


def _parse_github(content: bytes, base_url: str, max_items: int) -> list[FeedEntry]:
    rows = _json(content)
    if not isinstance(rows, list):
        raise FeedError("INVALID_GITHUB_RESPONSE", "GitHub response is not a list", retryable=False)
    entries = []
    for row in rows[:max_items]:
        if not isinstance(row, dict):
            continue
        url = canonicalize_url(row.get("html_url"), base_url)
        external = str(row.get("id") or row.get("tag_name") or url or "")
        title = strip_markup(str(row.get("name") or row.get("tag_name") or ""), 600)
        if not external or not title:
            continue
        author = row.get("author") if isinstance(row.get("author"), dict) else {}
        entries.append(
            _entry(
                external,
                url,
                title,
                strip_markup(row.get("body")),
                _parse_datetime(row.get("published_at")),
                author.get("login"),
                row,
            )
        )
    return _require_entries(entries, "GitHub response contains no releases")


def _parse_hugging_face(content: bytes, base_url: str, max_items: int) -> list[FeedEntry]:
    rows = _json(content)
    if not isinstance(rows, list):
        raise FeedError(
            "INVALID_HUGGING_FACE_RESPONSE", "Hugging Face response is not a list", retryable=False
        )
    entries = []
    for row in rows[:max_items]:
        if not isinstance(row, dict):
            continue
        paper = row.get("paper") if isinstance(row.get("paper"), dict) else row
        paper_id = str(paper.get("id") or paper.get("paperId") or paper.get("arxivId") or "")
        title = strip_markup(str(paper.get("title") or ""), 600)
        if not paper_id or not title:
            continue
        url = canonicalize_url(
            paper.get("url") or f"https://huggingface.co/papers/{paper_id}", base_url
        )
        entries.append(
            _entry(
                paper_id,
                url,
                title,
                strip_markup(paper.get("summary") or paper.get("abstract")),
                _parse_datetime(row.get("publishedAt") or paper.get("publishedAt")),
                None,
                row,
            )
        )
    return _require_entries(entries, "Hugging Face response contains no papers")


def _parse_openreview(content: bytes, base_url: str, max_items: int) -> list[FeedEntry]:
    payload = _json(content)
    rows = payload.get("notes") if isinstance(payload, dict) else None
    if not isinstance(rows, list):
        raise FeedError(
            "INVALID_OPENREVIEW_RESPONSE", "OpenReview response has no notes", retryable=False
        )
    entries = []
    for row in rows[:max_items]:
        if not isinstance(row, dict):
            continue
        note_id = str(row.get("id") or row.get("forum") or "")
        fields = row.get("content") if isinstance(row.get("content"), dict) else {}
        title = strip_markup(str(_value(fields.get("title")) or ""), 600)
        if not note_id or not title:
            continue
        authors = _value(fields.get("authors"))
        author = (
            ", ".join(str(value) for value in authors[:10]) if isinstance(authors, list) else None
        )
        url = f"https://openreview.net/forum?id={row.get('forum') or note_id}"
        entries.append(
            _entry(
                note_id,
                url,
                title,
                strip_markup(str(_value(fields.get("abstract")) or "")),
                _epoch_datetime(row.get("pdate") or row.get("cdate")),
                author,
                row,
            )
        )
    return _require_entries(entries, "OpenReview response contains no usable notes")


def _parse_hacker_news(
    content: bytes, base_url: str, config: dict[str, object], max_items: int
) -> list[FeedEntry]:
    payload = _json(content)
    rows = payload.get("hits") if isinstance(payload, dict) else None
    if not isinstance(rows, list):
        raise FeedError(
            "INVALID_HACKER_NEWS_RESPONSE", "Hacker News response has no hits", retryable=False
        )
    min_score = float(config.get("minScore") or 0)
    entries = []
    for row in rows:
        if not isinstance(row, dict) or float(row.get("points") or 0) < min_score:
            continue
        object_id = str(row.get("objectID") or "")
        title = strip_markup(str(row.get("title") or row.get("story_title") or ""), 600)
        if not object_id or not title:
            continue
        url = canonicalize_url(
            row.get("url") or f"https://news.ycombinator.com/item?id={object_id}", base_url
        )
        entries.append(
            _entry(
                object_id,
                url,
                title,
                strip_markup(row.get("story_text") or row.get("comment_text")),
                _parse_datetime(row.get("created_at")),
                row.get("author"),
                row,
            )
        )
        if len(entries) >= max_items:
            break
    return _require_entries(entries, "Hacker News response contains no matching stories")


def _entry(
    external_source: str,
    url: str | None,
    title: str,
    summary: str | None,
    published_at: datetime | None,
    author: str | None,
    payload: dict[str, object],
) -> FeedEntry:
    normalized = {
        "externalId": external_source,
        "url": url,
        "title": title,
        "summary": summary,
        "publishedAt": published_at.isoformat() if published_at else None,
        "author": author,
    }
    return FeedEntry(
        external_id=hashlib.sha256(external_source.encode("utf-8")).hexdigest(),
        original_url=url,
        canonical_url=url,
        title=title[:600],
        summary=summary,
        published_at=published_at,
        author_name=author,
        payload={**payload, "normalized": normalized},
        entry_hash=hashlib.sha256(
            json.dumps(normalized, ensure_ascii=False, sort_keys=True).encode("utf-8")
        ).hexdigest(),
    )


def _json(content: bytes) -> object:
    try:
        return json.loads(content)
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise FeedError("INVALID_JSON", str(exception), retryable=False) from exception


def _pattern(config: dict[str, object]) -> re.Pattern[str] | None:
    value = str(config.get("urlPattern") or "").strip()
    try:
        return re.compile(value) if value else None
    except re.error as exception:
        raise FeedError(
            "INVALID_CONFIG", f"Invalid urlPattern: {exception}", retryable=False
        ) from exception


def _value(value: object) -> object:
    return value.get("value") if isinstance(value, dict) and "value" in value else value


def _parse_datetime(value: object) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00")).astimezone(UTC)
    except ValueError:
        return None


def _epoch_datetime(value: object) -> datetime | None:
    try:
        number = float(value)
        return datetime.fromtimestamp(number / 1000 if number > 10_000_000_000 else number, tz=UTC)
    except (TypeError, ValueError, OSError):
        return None


def _require_entries(entries: list[FeedEntry], message: str) -> list[FeedEntry]:
    if not entries:
        raise FeedError("EMPTY_CONNECTOR_RESULT", message, retryable=False)
    return entries
