package com.example.planner.controller;

import com.example.planner.ai.GeneratedPlanService;
import com.example.planner.ai.PlanDraftService;
import com.example.planner.security.PlannerPrincipal;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.WebUtils;

import java.util.UUID;

@Controller
@RequestMapping("/ai/plans")
public class AiPlanController {
    private static final String SESSION_DRAFT = AiPlanController.class.getName() + ".draft";
    private final PlanDraftService drafts;
    private final GeneratedPlanService plans;

    public AiPlanController(PlanDraftService drafts, GeneratedPlanService plans) {
        this.drafts = drafts;
        this.plans = plans;
    }

    @GetMapping("/new")
    String form(@AuthenticationPrincipal PlannerPrincipal principal) {
        owner(principal);
        return "ai/form";
    }

    @PostMapping("/preview")
    String generate(@AuthenticationPrincipal PlannerPrincipal principal, HttpSession session, Model model,
                    @RequestParam String goal, @RequestParam String level, @RequestParam int durationDays,
                    @RequestParam(defaultValue = "") String notes) {
        long owner = owner(principal);
        try {
            var draft = new PendingDraft(UUID.randomUUID(), owner, drafts.generateEditable(goal, level, durationDays, notes));
            synchronized (WebUtils.getSessionMutex(session)) {
                session.setAttribute(SESSION_DRAFT, draft);
            }
            return preview(draft, model);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            model.addAttribute("error", failure.getMessage());
            return "ai/form";
        }
    }

    @PostMapping("/edit")
    String edit(@AuthenticationPrincipal PlannerPrincipal principal, HttpSession session, Model model,
                @RequestParam UUID draftId, @RequestParam String json) {
        synchronized (WebUtils.getSessionMutex(session)) {
            var draft = findDraft(session, owner(principal), draftId);
            try {
                var edited = new PendingDraft(UUID.randomUUID(), draft.owner(), drafts.editable(json));
                session.setAttribute(SESSION_DRAFT, edited);
                return preview(edited, model);
            } catch (IllegalArgumentException failure) {
                model.addAttribute("error", failure.getMessage());
                model.addAttribute("submittedJson", json.length() <= PlanDraftService.MAX_JSON_CHARS ? json : null);
                return preview(draft, model);
            }
        }
    }

    @PostMapping("/confirm")
    String confirm(@AuthenticationPrincipal PlannerPrincipal principal, HttpSession session, @RequestParam UUID draftId) {
        synchronized (WebUtils.getSessionMutex(session)) {
            var draft = findDraft(session, owner(principal), draftId);
            long planId = plans.confirm(draft.owner(), draft.value().json());
            session.removeAttribute(SESSION_DRAFT);
            return "redirect:/plans/" + planId;
        }
    }

    private static long owner(PlannerPrincipal principal) {
        if (principal == null || principal.id() <= 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return principal.id();
    }

    private static PendingDraft findDraft(HttpSession session, long owner, UUID draftId) {
        if (session.getAttribute(SESSION_DRAFT) instanceof PendingDraft draft
                && draft.owner() == owner && draft.id().equals(draftId)) {
            return draft;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bản nháp.");
    }

    private static String preview(PendingDraft draft, Model model) {
        model.addAttribute("draftId", draft.id());
        model.addAttribute("draftTitle", draft.value().tree().title());
        model.addAttribute("draftJson", draft.value().json());
        return "ai/preview";
    }

    private record PendingDraft(UUID id, long owner, PlanDraftService.EditableDraft value) {}
}
