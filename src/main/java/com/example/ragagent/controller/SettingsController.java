package com.example.ragagent.controller;

import com.example.ragagent.service.SettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * LLM/RAG settings page.
 *
 * <p>The read view lives at {@code /settings} (guest-open in no-auth/management-only; edit controls
 * are hidden client-side by {@code th:if="${isAdmin}"}). The mutating endpoints live under
 * {@code /admin/settings/**} so they inherit the existing {@code /admin/**} authorization
 * (SecurityConfig + NoAuthAutoLoginFilter) — ROLE_ADMIN in management-only mode — with no new
 * security wiring. Both return the single updated setting row as an HTMX fragment.
 */
@Controller
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/settings")
    public String settingsPage(Model model) {
        model.addAttribute("settings", settingsService.buildView());
        return "settings";
    }

    /** Persist a hot-editable override. Range/type errors → IllegalArgumentException → 400. */
    @PostMapping("/admin/settings/update")
    public String update(@RequestParam String key, @RequestParam String value, Model model) {
        settingsService.update(key, value);
        model.addAttribute("item", settingsService.editableItem(key));
        return "fragments/settings-item :: item";
    }

    /** Clear an override, reverting the key to its property default. */
    @PostMapping("/admin/settings/reset")
    public String reset(@RequestParam String key, Model model) {
        settingsService.reset(key);
        model.addAttribute("item", settingsService.editableItem(key));
        return "fragments/settings-item :: item";
    }
}
