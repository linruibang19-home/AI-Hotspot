package com.aihotspot.core.subscription;

import com.aihotspot.core.auth.AppUserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SubscriptionController {
    private final SubscriptionService service; public SubscriptionController(SubscriptionService service){this.service=service;}
    @GetMapping("/api/v1/subscriptions") @PreAuthorize("hasAuthority('subscription:manage')") public List<Map<String,Object>> list(@AuthenticationPrincipal AppUserPrincipal user){return service.list(user.id());}
    @PostMapping("/api/v1/subscriptions") @PreAuthorize("hasAuthority('subscription:manage')") public Map<String,Object> create(@Valid @RequestBody SubscriptionService.SubscriptionRequest request,@AuthenticationPrincipal AppUserPrincipal user){return Map.of("id",service.create(user.id(),request));}
    @PutMapping("/api/v1/subscriptions/{id}/status") @PreAuthorize("hasAuthority('subscription:manage')") public void status(@PathVariable UUID id,@RequestBody Map<String,String> body,@AuthenticationPrincipal AppUserPrincipal user){service.setStatus(user.id(),id,body.get("status"));}
    @PostMapping("/api/v1/subscriptions/{id}/send-now") @PreAuthorize("hasAuthority('subscription:manage')") public Map<String,Object> sendNow(@PathVariable UUID id,@AuthenticationPrincipal AppUserPrincipal user){return service.sendNow(user.id(),id);}
    @GetMapping("/api/v1/admin/deliveries") @PreAuthorize("hasRole('ADMIN')") public List<Map<String,Object>> deliveries(){return service.deliveries();}
    @PostMapping("/api/v1/admin/deliveries/{id}/retry") @PreAuthorize("hasRole('ADMIN')") public void retry(@PathVariable UUID id){service.retry(id);}
    @GetMapping("/api/v1/public/unsubscribe/{token}") public ResponseEntity<String> unsubscribe(@PathVariable String token){boolean result=service.unsubscribe(token,"ONE_CLICK");return ResponseEntity.ok().header("content-type","text/html;charset=UTF-8").body(result?"<h1>已退订 AI Hotspot 邮件</h1>":"<h1>退订链接无效</h1>");}
}
