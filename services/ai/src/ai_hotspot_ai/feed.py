import calendar
import hashlib
import html
import ipaddress
import json
import re
import socket
from dataclasses import dataclass
from datetime import UTC, datetime
from html.parser import HTMLParser
from urllib.parse import urljoin, urlsplit, urlunsplit

import feedparser
import httpx

UNSAFE_XML_PATTERN = re.compile(rb"<!\s*(?:DOCTYPE|ENTITY)\b", re.IGNORECASE)


class FeedError(RuntimeError):
    def __init__(self, code: str, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable


@dataclass(frozen=True, slots=True)
class FetchRequest:
    url: str
    etag: str | None
    last_modified: str | None
    timeout_seconds: int
    max_response_bytes: int
    user_agent: str
    accept: str | None = None


@dataclass(frozen=True, slots=True)
class FetchResponse:
    status_code: int
    final_url: str
    content: bytes
    content_type: str | None
    etag: str | None
    last_modified: str | None


@dataclass(frozen=True, slots=True)
class FeedEntry:
    external_id: str
    original_url: str | None
    canonical_url: str | None
    title: str
    summary: str | None
    published_at: datetime | None
    author_name: str | None
    payload: dict[str, object]
    entry_hash: str


class _TextExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []

    def handle_data(self, data: str) -> None:
        self.parts.append(data)


def strip_markup(value: str | None, limit: int = 4000) -> str | None:
    if not value:
        return None
    parser = _TextExtractor()
    parser.feed(value)
    text = " ".join("".join(parser.parts).split())
    return html.unescape(text)[:limit] or None


def canonicalize_url(value: str | None, base_url: str) -> str | None:
    if not value:
        return None
    joined = urljoin(base_url, value.strip())
    parsed = urlsplit(joined)
    if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname:
        return None
    hostname = parsed.hostname.lower().encode("idna").decode("ascii")
    port = parsed.port
    netloc = hostname if port is None else f"{hostname}:{port}"
    path = parsed.path or "/"
    return urlunsplit((parsed.scheme.lower(), netloc, path, parsed.query, ""))


def validate_public_url(value: str) -> None:
    parsed = urlsplit(value)
    if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname:
        raise FeedError("INVALID_URL", "Feed URL must be public HTTP/HTTPS", retryable=False)
    if parsed.username or parsed.password or parsed.port not in {None, 80, 443}:
        raise FeedError(
            "INVALID_URL", "Feed URL contains disallowed credentials or port", retryable=False
        )
    try:
        addresses = socket.getaddrinfo(parsed.hostname, parsed.port or 443, type=socket.SOCK_STREAM)
    except OSError as exception:
        raise FeedError(
            "DNS_FAILURE", f"DNS lookup failed: {exception}", retryable=True
        ) from exception
    for address in addresses:
        ip = ipaddress.ip_address(address[4][0])
        if not ip.is_global:
            raise FeedError(
                "SSRF_BLOCKED", f"Feed URL resolves to non-public address {ip}", retryable=False
            )


def fetch_feed(request: FetchRequest) -> FetchResponse:
    headers = {
        "Accept": request.accept
        or "application/atom+xml, application/rss+xml, application/xml, text/xml;q=0.9",
        "User-Agent": request.user_agent,
    }
    if request.etag:
        headers["If-None-Match"] = request.etag
    if request.last_modified:
        headers["If-Modified-Since"] = request.last_modified
    current_url = request.url
    try:
        with httpx.Client(timeout=request.timeout_seconds, follow_redirects=False) as client:
            for _ in range(4):
                validate_public_url(current_url)
                with client.stream("GET", current_url, headers=headers) as response:
                    if response.status_code in {301, 302, 303, 307, 308}:
                        location = response.headers.get("location")
                        if not location:
                            raise FeedError(
                                "INVALID_REDIRECT",
                                "Redirect response has no Location",
                                retryable=False,
                            )
                        current_url = urljoin(current_url, location)
                        continue
                    if response.status_code == 304:
                        return FetchResponse(
                            304,
                            current_url,
                            b"",
                            response.headers.get("content-type"),
                            response.headers.get("etag"),
                            response.headers.get("last-modified"),
                        )
                    if response.status_code >= 500 or response.status_code in {408, 425, 429}:
                        raise FeedError(
                            "UPSTREAM_TEMPORARY",
                            f"Feed returned HTTP {response.status_code}",
                            retryable=True,
                        )
                    if response.status_code < 200 or response.status_code >= 300:
                        raise FeedError(
                            "UPSTREAM_REJECTED",
                            f"Feed returned HTTP {response.status_code}",
                            retryable=False,
                        )
                    declared_length = response.headers.get("content-length")
                    if declared_length and int(declared_length) > request.max_response_bytes:
                        raise FeedError(
                            "RESPONSE_TOO_LARGE",
                            "Feed exceeds configured response size",
                            retryable=False,
                        )
                    chunks: list[bytes] = []
                    size = 0
                    for chunk in response.iter_bytes():
                        size += len(chunk)
                        if size > request.max_response_bytes:
                            raise FeedError(
                                "RESPONSE_TOO_LARGE",
                                "Feed exceeds configured response size",
                                retryable=False,
                            )
                        chunks.append(chunk)
                    return FetchResponse(
                        response.status_code,
                        current_url,
                        b"".join(chunks),
                        response.headers.get("content-type"),
                        response.headers.get("etag"),
                        response.headers.get("last-modified"),
                    )
        raise FeedError("TOO_MANY_REDIRECTS", "Feed exceeded redirect limit", retryable=False)
    except FeedError:
        raise
    except (httpx.TimeoutException, httpx.NetworkError) as exception:
        raise FeedError("NETWORK_FAILURE", str(exception), retryable=True) from exception


def parse_feed(content: bytes, base_url: str, max_items: int) -> list[FeedEntry]:
    if UNSAFE_XML_PATTERN.search(content[:65536]):
        raise FeedError(
            "UNSAFE_XML", "DTD and entity declarations are not accepted", retryable=False
        )
    parsed = feedparser.parse(content, resolve_relative_uris=False, sanitize_html=True)
    if parsed.bozo and not parsed.entries:
        raise FeedError(
            "INVALID_FEED", f"Feed parsing failed: {parsed.bozo_exception}", retryable=False
        )
    entries: list[FeedEntry] = []
    for source in parsed.entries[:max_items]:
        title = strip_markup(str(source.get("title") or ""), 600)
        if not title:
            continue
        original_url = canonicalize_url(source.get("link"), base_url)
        external_source = source.get("id") or source.get("guid") or original_url
        published_at = _entry_datetime(source)
        content_values = source.get("content") or [{}]
        summary = strip_markup(
            source.get("summary") or source.get("description") or content_values[0].get("value"),
            4000,
        )
        if not external_source:
            external_source = f"{title}|{published_at.isoformat() if published_at else ''}"
        external_id = hashlib.sha256(str(external_source).encode("utf-8")).hexdigest()
        normalized = {
            "externalId": str(external_source)[:2000],
            "url": original_url,
            "title": title,
            "summary": summary,
            "publishedAt": published_at.isoformat() if published_at else None,
            "author": strip_markup(source.get("author"), 400),
            "tags": [
                str(tag.get("term"))[:200] for tag in source.get("tags", [])[:20] if tag.get("term")
            ],
        }
        entry_hash = hashlib.sha256(
            json.dumps(normalized, ensure_ascii=False, sort_keys=True).encode("utf-8")
        ).hexdigest()
        entries.append(
            FeedEntry(
                external_id=external_id,
                original_url=original_url,
                canonical_url=original_url,
                title=title,
                summary=summary,
                published_at=published_at,
                author_name=normalized["author"],
                payload=normalized,
                entry_hash=entry_hash,
            )
        )
    if not entries:
        raise FeedError("EMPTY_FEED", "Feed contains no usable entries", retryable=False)
    return entries


def _entry_datetime(source: dict[str, object]) -> datetime | None:
    for key in ("published_parsed", "updated_parsed", "created_parsed"):
        value = source.get(key)
        if value:
            return datetime.fromtimestamp(calendar.timegm(value), tz=UTC)
    return None
