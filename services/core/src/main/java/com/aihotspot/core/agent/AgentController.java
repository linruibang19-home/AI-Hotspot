package com.aihotspot.core.agent;

import com.aihotspot.core.auth.AppUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/v1/agents") @PreAuthorize("hasAuthority('agent:use')")
public class AgentController {
    private final AgentService service;public AgentController(AgentService service){this.service=service;}
    @GetMapping("/definitions") public List<Map<String,Object>> definitions(){return service.definitions();}
    @GetMapping("/runs") public List<Map<String,Object>> runs(@AuthenticationPrincipal AppUserPrincipal user){return service.runs(user.id());}
    @GetMapping("/runs/{id}") public Map<String,Object> detail(@PathVariable UUID id,@AuthenticationPrincipal AppUserPrincipal user){return service.detail(user.id(),id);}
    @PostMapping("/runs") public Map<String,Object> start(@Valid @RequestBody StartRequest body,@AuthenticationPrincipal AppUserPrincipal user){return Map.of("id",service.start(user,body.agentCode(),body.objective()));}
    @PostMapping("/runs/{id}/cancel") public void cancel(@PathVariable UUID id,@AuthenticationPrincipal AppUserPrincipal user){service.cancel(user.id(),id);}
    @PostMapping("/approvals/{id}") @PreAuthorize("hasAuthority('agent:approve')") public void decide(@PathVariable UUID id,@RequestBody Decision body,@AuthenticationPrincipal AppUserPrincipal user){service.decide(user,id,body.approve(),body.note());}
    @GetMapping("/approvals") @PreAuthorize("hasAuthority('agent:approve')") public List<Map<String,Object>> approvals(){return service.pendingApprovals();}
    public record StartRequest(@NotBlank String agentCode,@NotBlank String objective){} public record Decision(boolean approve,String note){}
}
