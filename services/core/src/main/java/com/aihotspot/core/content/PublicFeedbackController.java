package com.aihotspot.core.content;

import com.aihotspot.core.auth.AppUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/feedback")
public class PublicFeedbackController {
    private final ContentGovernanceService service;
    public PublicFeedbackController(ContentGovernanceService service) { this.service = service; }

    @PostMapping
    public FeedbackResponse submit(@Valid @RequestBody FeedbackRequest body,
                                   @AuthenticationPrincipal AppUserPrincipal actor) {
        UUID id = service.createTicket(body.contentId(), body.eventId(), body.type(), "NORMAL",
                body.reason(), body.contactEmail(), actor);
        return new FeedbackResponse(id, "OPEN");
    }
    public record FeedbackRequest(UUID contentId, UUID eventId, @NotBlank String type,
            @NotBlank @Size(min = 8, max = 2000) String reason, @Email @Size(max = 320) String contactEmail) {}
    public record FeedbackResponse(UUID id, String status) {}
}
