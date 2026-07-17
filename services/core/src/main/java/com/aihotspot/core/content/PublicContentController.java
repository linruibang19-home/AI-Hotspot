package com.aihotspot.core.content;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/contents")
public class PublicContentController {

    private final PublicContentService service;

    public PublicContentController(PublicContentService service) {
        this.service = service;
    }

    @GetMapping
    public PublicContentService.Page list(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return service.list(false, cursor, limit);
    }

    @GetMapping("/featured")
    public PublicContentService.Page featured(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return service.list(true, cursor, limit);
    }

    @GetMapping("/{id}")
    public PublicContentMapper.PublicContentView detail(@PathVariable UUID id) {
        return service.detail(id);
    }
}
