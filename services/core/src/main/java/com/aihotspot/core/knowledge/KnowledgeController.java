package com.aihotspot.core.knowledge;

import com.aihotspot.core.auth.AppUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/research")
@PreAuthorize("hasAuthority('research:use')")
public class KnowledgeController {
    private final ResearchService research;
    public KnowledgeController(ResearchService research){this.research=research;}
    @GetMapping("/sessions") public List<Map<String,Object>> sessions(@AuthenticationPrincipal AppUserPrincipal user){return research.sessions(user.id());}
    @PostMapping("/query") public ResearchService.ResearchResult query(@Valid @RequestBody QueryRequest request,@AuthenticationPrincipal AppUserPrincipal user){
        return research.ask(user,request.sessionId(),request.question(),request.filters()==null?Map.of():request.filters());
    }
    public record QueryRequest(UUID sessionId,@NotBlank String question,Map<String,Object> filters){}
}
