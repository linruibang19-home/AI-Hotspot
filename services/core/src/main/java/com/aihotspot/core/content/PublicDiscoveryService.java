package com.aihotspot.core.content;

import com.aihotspot.core.api.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PublicDiscoveryService {
    private final PublicDiscoveryMapper mapper;
    public PublicDiscoveryService(PublicDiscoveryMapper mapper) { this.mapper = mapper; }

    public List<PublicContentMapper.PublicContentView> search(String query, String sourceType,
            String contentType, int requestedLimit) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.length() < 2) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "SEARCH_QUERY_TOO_SHORT", "搜索关键词至少需要 2 个字符");
        return mapper.search(normalized, normalizeFilter(sourceType), normalizeFilter(contentType), limit(requestedLimit, 50));
    }
    public List<PublicDiscoveryMapper.TopicView> topics() { return mapper.listTopics(); }
    public TopicDetail topic(String slug, int requestedLimit) {
        PublicDiscoveryMapper.TopicView topic = mapper.findTopic(slug);
        if (topic == null) throw new ApiException(HttpStatus.NOT_FOUND, "TOPIC_NOT_FOUND", "主题不存在");
        return new TopicDetail(topic, mapper.listTopicContents(topic.queryText(), limit(requestedLimit, 50)));
    }
    public List<PublicDiscoveryMapper.EventView> events(int requestedLimit) { return mapper.listEvents(limit(requestedLimit, 20)); }
    public EventDetail event(UUID id) {
        PublicDiscoveryMapper.EventView event = mapper.findEvent(id);
        if (event == null) throw new ApiException(HttpStatus.NOT_FOUND, "PUBLIC_EVENT_NOT_FOUND", "公开事件不存在");
        return new EventDetail(event, mapper.listEventContents(id, 100));
    }
    private int limit(int requested, int fallback) { return Math.min(Math.max(requested <= 0 ? fallback : requested, 1), 100); }
    private String normalizeFilter(String value) { return value == null || value.isBlank() || "ALL".equalsIgnoreCase(value) ? null : value.strip().toUpperCase(); }
    public record TopicDetail(PublicDiscoveryMapper.TopicView topic, List<PublicContentMapper.PublicContentView> items) {}
    public record EventDetail(PublicDiscoveryMapper.EventView event, List<PublicContentMapper.PublicContentView> items) {}
}
