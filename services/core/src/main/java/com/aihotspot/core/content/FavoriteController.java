package com.aihotspot.core.content;

import com.aihotspot.core.auth.AppUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/favorites")
public class FavoriteController {
    private final FavoriteService service;
    public FavoriteController(FavoriteService service) { this.service = service; }
    @GetMapping public List<PublicContentMapper.PublicContentView> list(@AuthenticationPrincipal AppUserPrincipal user) { return service.list(user.id()); }
    @GetMapping("/{type}/{id}") public FavoriteState state(@PathVariable String type, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal user) { return new FavoriteState(service.exists(user.id(), type, id)); }
    @PostMapping @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@Valid @RequestBody FavoriteRequest body, @AuthenticationPrincipal AppUserPrincipal user) { service.add(user.id(), body.targetType(), body.targetId()); }
    @DeleteMapping("/{type}/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String type, @PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal user) { service.remove(user.id(), type, id); }
    public record FavoriteRequest(@NotNull String targetType, @NotNull UUID targetId) {}
    public record FavoriteState(boolean favorite) {}
}
