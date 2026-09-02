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

    /**
     * Enable/disable a registered LLM provider at runtime (§A, in-memory — resets on restart).
     * Unknown name / disabling the last enabled provider → IllegalArgumentException → 400. Returns the
     * refreshed providers table so the whole block (incl. any name-shared load-balanced pair) stays in sync.
     */
    @PostMapping("/admin/settings/provider/toggle")
    public String toggleProvider(@RequestParam String name, @RequestParam boolean enabled, Model model) {
        model.addAttribute("providers", settingsService.setProviderEnabled(name, enabled));
        model.addAttribute("probeResult", null);
        return "fragments/settings-providers :: providers";
    }

    /**
     * Ask every registered LOCAL provider for its context window again (§6.26 A5).
     *
     * <p>The startup probe is a snapshot: reload the model at a different size, or let LM Studio
     * load it JIT after boot, and the app keeps answering from a stale — or absent — number, which
     * silently mis-sizes every input budget from then on. This is the operator-triggered refresh.
     * It is a button rather than a timer on purpose (see
     * {@link SettingsService#reprobeContextWindows()}): a budget that moves on its own makes the
     * same question return a different amount of evidence depending on when it was asked.
     *
     * <p>Returns the whole providers table so the 컨텍스트 column refreshes in place, with the
     * per-provider outcome rendered above it — including the restart notice, since a re-probe can
     * only fix the input budget and never the output reservation baked into the provider bean.
     */
    @PostMapping("/admin/settings/context-window/reprobe")
    public String reprobeContextWindows(Model model) {
        model.addAttribute("probeResult", settingsService.reprobeContextWindows());
        model.addAttribute("providers", settingsService.providerRows());
        return "fragments/settings-providers :: providers";
    }
}
