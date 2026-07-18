package com.aihotspot.core.content;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public")
public class PublicDiscoveryController {
    private final PublicDiscoveryService service;
    public PublicDiscoveryController(PublicDiscoveryService service) { this.service = service; }

    @GetMapping("/search")
    public List<PublicContentMapper.PublicContentView> search(@RequestParam String query,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String contentType,
            @RequestParam(defaultValue = "30") int limit) {
        return service.search(query, sourceType, contentType, limit);
    }
    @GetMapping("/topics") public List<PublicDiscoveryMapper.TopicView> topics() { return service.topics(); }
    @GetMapping("/topics/{slug}") public PublicDiscoveryService.TopicDetail topic(@PathVariable String slug,
            @RequestParam(defaultValue = "30") int limit) { return service.topic(slug, limit); }
    @GetMapping("/events") public List<PublicDiscoveryMapper.EventView> events(@RequestParam(defaultValue = "10") int limit) { return service.events(limit); }
    @GetMapping("/events/{id}") public PublicDiscoveryService.EventDetail event(@PathVariable UUID id) { return service.event(id); }
}
