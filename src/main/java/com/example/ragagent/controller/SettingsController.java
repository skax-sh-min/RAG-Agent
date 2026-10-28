package com.example.ragagent.controller;

import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.service.ProviderCatalog;
import com.example.ragagent.service.SettingsService;
import com.example.ragagent.service.ThinkingPreviewService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;
import java.util.List;

/**
 * LLM/RAG settings page.
 *
 * <p>The read view lives at {@code /settings} (guest-open in no-auth/management-only; edit controls
 * are hidden client-side by {@code th:if="${isAdmin}"}). The mutating endpoints live under
 * {@code /admin/settings/**} so they inherit the existing {@code /admin/**} authorization
 * (SecurityConfig + NoAuthAutoLoginFilter) — ROLE_ADMIN in management-only mode — with no new
 * security wiring. Both return the single updated setting row as an HTMX fragment.
 *
 * <p><b>생각(추론) 수준 카드</b>(PLAN §6.29 ⑦)는 관리자 전용이고 읽기까지 막는다 — 그래서 카드의 모든 엔드포인트가
 * {@code /admin/settings/**} 아래에 있다. 화면에서 감추는 것은 보안이 아니다(실제 게이트는 {@code SecurityConfig} 의
 * {@code /admin/** → hasRole("ADMIN")}). {@code /settings} 페이지는 관리자일 때만 카드의 자리표시자를 그리고, 내용은 아래
 * {@link #thinkingCard} 가 채운다.
 */
@Controller
public class SettingsController {

    /** 컨텍스트 창이 바뀌면(재탐지) 예산 숫자가 전부 바뀐다 — 카드가 이 이벤트를 듣고 스스로 다시 불러온다. */
    static final String THINKING_STALE_EVENT = "thinking-preview-stale";

    private final SettingsService settingsService;
    private final ThinkingPreviewService thinkingPreview;
    private final ProviderCatalog providerCatalog;

    public SettingsController(SettingsService settingsService, ThinkingPreviewService thinkingPreview,
                              ProviderCatalog providerCatalog) {
        this.settingsService = settingsService;
        this.thinkingPreview = thinkingPreview;
        this.providerCatalog = providerCatalog;
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
        return rowFor(key, model);
    }

    /** Clear an override, reverting the key to its property default. */
    @PostMapping("/admin/settings/reset")
    public String reset(@RequestParam String key, Model model) {
        settingsService.reset(key);
        return rowFor(key, model);
    }

    /**
     * 갱신 뒤에 돌려줄 조각 — 일반 항목은 한 줄({@code settings-item}), 생각 수준 키({@code llm.thinking.<site>})는 그 사이트의
     * 행을 <b>새로 계산해서</b> 돌려준다(저장한 수준이 예약·예산·배지를 바꾸므로 값 칸만 갈아끼우면 낡은 숫자가 남는다).
     */
    private String rowFor(String key, Model model) {
        var site = ThinkingSite.bySettingsKey(key);
        if (site.isPresent()) {
            model.addAttribute("row", thinkingPreview.row(site.get()));
            return "fragments/settings-thinking :: row";
        }
        model.addAttribute("item", settingsService.editableItem(key));
        return "fragments/settings-item :: item";
    }

    /**
     * 생각 수준 카드 전체(PLAN §6.29 ⑦) — 페이지를 열 때와 [다시 계산]·창 재탐지 뒤에 불린다. 계산은 산술과 번들 길이
     * 추정뿐이라 LLM·DB 호출이 없다. <b>읽기도 관리자만</b> 한다: 프로바이더 이름·창 크기·관측값이 드러나는데 게스트가 그 값으로
     * 할 수 있는 일이 없다.
     */
    @GetMapping("/admin/settings/thinking")
    public String thinkingCard(Model model) {
        model.addAttribute("thinking", thinkingPreview.preview());
        return "fragments/settings-thinking :: card";
    }

    /**
     * 프로바이더 표의 <b>상태 칸</b>을 서버에 실제로 접속해 본 결과로 채운다 — {@code /settings} 가 "확인 중" 으로 그려 둔 칸을
     * 이 응답이 같은 id 로 통째로 바꿔 끼운다(HTMX out-of-band). 페이지 안에서 직접 접속하지 않는 이유: 죽은 서버의 연결
     * 타임아웃이 설정 화면 전체를 몇 초씩 붙잡기 때문이다.
     *
     * <p>{@code /settings} 와 같은 권한이다(게스트 개방 모드에서는 게스트도 본다) — 보이는 것은 상태 배지와 사유 한 줄뿐이고,
     * 접속 주소는 표가 이미 같은 사람에게 보여 준다. 대상은 설정에 적힌 서버뿐이라 요청이 접속 대상을 정하지 못한다.
     */
    @GetMapping("/settings/llm-status")
    public String providerStatus(Model model) {
        model.addAttribute("providers", settingsService.refreshProviderConnections());
        return "fragments/settings-providers :: statuses";
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
    public String reprobeContextWindows(Model model, HttpServletResponse response) {
        model.addAttribute("probeResult", settingsService.reprobeContextWindows());
        model.addAttribute("providers", settingsService.providerRows());
        // 창이 바뀌면 생각 수준 카드의 예산 숫자가 전부 달라진다 — 카드가 이 이벤트를 듣고 다시 불러온다.
        response.setHeader("HX-Trigger", THINKING_STALE_EVENT);
        return "fragments/settings-providers :: providers";
    }

    // ── 프로바이더 편집 (ProviderCatalog) ─────────────────────────────────────
    //
    // 주소·모델·type·role·우선순위·추가·삭제는 저장만 하고 다음 기동에, 컨텍스트는 저장 즉시 반영된다. API 키는 받지 않는다.
    // 성공하면 표 조각 전체(#llm-providers)를 돌려주고, 실패하면 편집 칸(#provider-editor)에 사유와 함께 폼을 다시 그린다 —
    // 400 으로 돌려주면 화면에는 "요청 실패(400)" 토스트만 남아 무엇을 고칠지 알 수 없다.

    /** 편집 폼 — {@code name} 이 없으면 새 프로바이더(LOCAL). */
    @GetMapping("/admin/settings/provider/editor")
    public String providerEditor(@RequestParam(required = false) String name, Model ui) {
        fillEditor(ui, providerCatalog.formFor(name), null);
        return "fragments/settings-providers :: editor";
    }

    @PostMapping("/admin/settings/provider/save")
    public String saveProvider(@RequestParam(required = false) String name,
                               @RequestParam(required = false) String baseUrl,
                               @RequestParam(required = false) String model,
                               @RequestParam(required = false) String type,
                               @RequestParam(required = false) String role,
                               @RequestParam(required = false) String priority,
                               @RequestParam(required = false) String contextSize,
                               @RequestParam(defaultValue = "false") boolean create,
                               Model ui, HttpServletResponse response) {
        ProviderCatalog.Edit edit = null;
        try {
            edit = new ProviderCatalog.Edit(name, baseUrl, model, type, role,
                    parseInt(priority, "우선순위"), parseInt(contextSize, "컨텍스트"), create);
            ProviderCatalog.SaveResult result = providerCatalog.save(edit);
            return providersWith(result, ui, response);
        } catch (IllegalArgumentException e) {
            ProviderCatalog.Edit shown = edit != null ? edit
                    : new ProviderCatalog.Edit(name, baseUrl, model, type, role, null, null, create);
            fillEditor(ui, shown, e.getMessage());
            retargetToEditor(response);
            return "fragments/settings-providers :: editor";
        }
    }

    /** 삭제 — properties 정의는 재기동 뒤 숨기고, 아직 돌지 않는 추가분은 바로 지운다. */
    @PostMapping("/admin/settings/provider/delete")
    public String deleteProvider(@RequestParam String name, Model ui, HttpServletResponse response) {
        try {
            return providersWith(providerCatalog.delete(name), ui, response);
        } catch (IllegalArgumentException e) {
            return editorError(e, ui, response);
        }
    }

    /** 되돌리기 — 화면에 남긴 편집을 지운다(컨텍스트는 즉시, 나머지는 재기동 뒤). */
    @PostMapping("/admin/settings/provider/revert")
    public String revertProvider(@RequestParam String name, Model ui, HttpServletResponse response) {
        try {
            return providersWith(providerCatalog.revert(name), ui, response);
        } catch (IllegalArgumentException e) {
            return editorError(e, ui, response);
        }
    }

    private String providersWith(ProviderCatalog.SaveResult result, Model ui, HttpServletResponse response) {
        ui.addAttribute("providers", settingsService.providerRows());
        ui.addAttribute("probeResult", null);
        ui.addAttribute("providerEditResult", result);
        // 컨텍스트가 바뀌면 생각 수준 카드의 예산 숫자가 달라진다 — 재탐지와 같은 이벤트.
        if (result.contextChanged()) response.setHeader("HX-Trigger", THINKING_STALE_EVENT);
        return "fragments/settings-providers :: providers";
    }

    private void fillEditor(Model ui, ProviderCatalog.Edit form, String error) {
        ui.addAttribute("form", form);
        ui.addAttribute("formError", error);
        String name = form.name();
        ui.addAttribute("formDeclaredContext",
                form.create() || name == null ? null : providerCatalog.declaredContext(name).orElse(null));
        ui.addAttribute("formHasApiKey", !form.create() && name != null && providerCatalog.hasApiKey(name));
        // 수동 컨텍스트는 탐지가 안 될 때만 — 선언됐거나 탐지값이 있으면 칸을 잠근다(declared / probed).
        ui.addAttribute("formContextLock",
                form.create() || name == null ? null : providerCatalog.contextLock(name).orElse(null));
        ui.addAttribute("formRoles", List.of("LOCAL", "NORMAL", "PREMIUM"));
        ui.addAttribute("formTypes", Arrays.stream(TaskType.values()).map(Enum::name).toList());
    }

    /** 표의 버튼(삭제·되돌리기)이 거절됐다 — 사유만 편집 칸에. */
    private String editorError(IllegalArgumentException e, Model ui, HttpServletResponse response) {
        ui.addAttribute("formError", e.getMessage());
        retargetToEditor(response);
        return "fragments/settings-providers :: editor-error";
    }

    private static void retargetToEditor(HttpServletResponse response) {
        response.setHeader("HX-Retarget", "#provider-editor");
        response.setHeader("HX-Reswap", "innerHTML");
    }

    private static Integer parseInt(String raw, String label) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "는 숫자여야 합니다: " + raw);
        }
    }
}
