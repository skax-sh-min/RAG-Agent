package com.example.ragagent.controller;

import com.example.ragagent.security.CurrentUser;
import com.example.ragagent.service.CuratedSubmissionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * 청크 추가 게시판 (사용자 측) — post a chunk proposal, watch its review status.
 *
 * <p>Guest-open in every auth mode, exactly like chat: the write it performs is a pending row that
 * has no effect on search until an admin approves it. Everything is scoped to
 * {@link CurrentUser#userId()} — note that in no-auth mode this is only a per-visitor id when
 * {@code app.auth.guest-identity} is set to something other than the default {@code shared}
 * (see {@code GuestIdentityResolver}); under {@code shared} every guest sees one common list.
 */
@Controller
@RequestMapping("/curated/submissions")
public class CuratedSubmissionController {

    private static final int PAGE_SIZE = 20;

    private final CuratedSubmissionService service;
    private final CurrentUser currentUser;

    public CuratedSubmissionController(CuratedSubmissionService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /**
     * "내 제안" page — the write form plus this user's submissions and their review outcomes.
     * Rendering the page also clears the author's unread-badge state: opening the list <em>is</em>
     * reading it, so a separate "확인" click would be busywork.
     */
    @GetMapping
    public String page(@RequestParam(defaultValue = "0") int offset, Model model) {
        String userId = currentUser.userId();
        service.markAllReadForAuthor(userId);
        model.addAttribute("submissions", service.listMine(userId, offset, PAGE_SIZE));
        model.addAttribute("offset", offset);
        model.addAttribute("pageSize", PAGE_SIZE);
        model.addAttribute("chunkSize", service.chunkSizeForBody());
        model.addAttribute("maxTitleLength", CuratedSubmissionService.MAX_TITLE_LEN);
        model.addAttribute("maxTags", com.example.ragagent.model.TagUtils.MAX_TAGS);
        return "curated-submissions";
    }

    /**
     * Plain form POST + redirect (not HTMX): validation failures surface as a flash message on the
     * re-rendered page. A JSON error from {@code GlobalExceptionHandler} would be the wrong shape
     * here — this is an HTML form, not an API call.
     */
    @PostMapping
    public String submit(@RequestParam String title,
                         @RequestParam String body,
                         @RequestParam(required = false) String tags,
                         RedirectAttributes flash) {
        try {
            service.submit(currentUser.userId(), title, body,
                    com.example.ragagent.model.TagUtils.parseTagList(tags));
            flash.addFlashAttribute("submitSuccess",
                    "제안이 등록되었습니다. 관리자가 검토 후 임베딩을 실행하면 검색에 반영됩니다.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("submitError", e.getMessage());
            // Hand the draft back so a rejection doesn't wipe what the user typed.
            flash.addFlashAttribute("draftTitle", title);
            flash.addFlashAttribute("draftBody", body);
            flash.addFlashAttribute("draftTags", tags);
        }
        return "redirect:/curated/submissions";
    }

    @PostMapping("/{id}/withdraw")
    public String withdraw(@PathVariable long id, RedirectAttributes flash) {
        if (service.withdraw(id, currentUser.userId())) {
            flash.addFlashAttribute("submitSuccess", "제안을 철회했습니다.");
        } else {
            flash.addFlashAttribute("submitError", "이미 처리된 제안은 철회할 수 없습니다.");
        }
        return "redirect:/curated/submissions";
    }

    /**
     * Header-badge poll (~60 s) — reviewed submissions this user hasn't looked at yet. Scoped to
     * the caller, so it leaks nothing regardless of auth mode.
     */
    @GetMapping("/unread-count")
    @ResponseBody
    public Map<String, Integer> unreadCount() {
        return Map.of("count", service.countUnreadForAuthor(currentUser.userId()));
    }
}
