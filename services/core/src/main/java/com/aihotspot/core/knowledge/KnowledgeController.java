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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/research")
@PreAuthorize("hasAuthority('research:use')")
public class KnowledgeController {
    private final ResearchService research;
    private final ResearchFeedbackService feedback;
    public KnowledgeController(ResearchService research,ResearchFeedbackService feedback){
        this.research=research;this.feedback=feedback;
    }
    @GetMapping("/sessions") public List<ResearchService.ResearchSessionSummary> sessions(@AuthenticationPrincipal AppUserPrincipal user){return research.sessions(user.id());}
    @GetMapping("/sessions/{sessionId}") public ResearchService.ResearchSessionView session(
            @PathVariable UUID sessionId,@AuthenticationPrincipal AppUserPrincipal user){
        return research.session(user.id(),sessionId);
    }
    @PostMapping("/query") public ResearchService.ResearchResult query(@Valid @RequestBody QueryRequest request,@AuthenticationPrincipal AppUserPrincipal user){
        return research.ask(user,request.sessionId(),request.question(),request.filters()==null?Map.of():request.filters());
    }
    @PostMapping("/runs/{runId}/feedback")
    public ResearchFeedbackService.Feedback feedback(
            @PathVariable UUID runId,@Valid @RequestBody FeedbackRequest request,
            @AuthenticationPrincipal AppUserPrincipal user){
        return feedback.submit(user.id(),runId,request.rating(),request.issueCategories(),request.comment());
    }
    public record QueryRequest(UUID sessionId,@NotBlank String question,Map<String,Object> filters){}
    public record FeedbackRequest(@NotBlank String rating,List<String> issueCategories,String comment){}
}
