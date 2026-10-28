package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.ProviderOverlay;
import com.example.ragagent.config.ProviderOverride;
import com.example.ragagent.llm.ContextWindowProbe;
import com.example.ragagent.llm.ProviderConnectivity;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.SettingsView.ProviderEdit;
import com.example.ragagent.repository.ProviderOverrideRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * {@code /settings} 의 LLM 라우팅 표에서 관리자가 고친 프로바이더 정의 — 저장·검증·기동 때의 적용.
 *
 * <p><b>무엇이 언제 반영되는가.</b> 라우터는 기동 때 프로바이더마다 데코레이터 체인과 동시성 게이트를 만들어 고정 목록으로 든다
 * ({@code LlmConfig.llmRouter}). 그래서 주소·모델·type·role·우선순위·추가·삭제는 <b>저장만 하고 다음 기동에</b> 반영한다 —
 * 기동 때 이 클래스가 {@code application.properties} 목록 위에 편집을 얹은 목록을 한 번 계산해 굳히고
 * ({@link ProviderOverlay}), {@code props.llmSafe().providers()} 가 그 굳은 목록을 돌려준다({@code AppProperties.bindProviderOverlay}).
 * 라우터 재구성 없이 반영할 수 있는 <b>컨텍스트 창은 저장 즉시</b> {@link ProviderContextWindows} 에 기록한다(수동 입력 =
 * {@link ProviderContextWindows.Source#MANUAL}). 수동 입력은 <b>탐지가 안 될 때의 대체값</b>이다 — 선언됐거나 탐지값이 있으면
 * 받지 않고({@link #contextLock}), 나중에 탐지(기동·재탐지 버튼·자동 재탐지)가 성공하면 탐지값이 덮으며, 탐지가 실패하면 남는다.
 *
 * <p><b>화면 값이 기동을 막아서는 안 된다.</b> LOCAL 프로바이더는 기동 때 모델명을 검증하고(G3) 실패하면 기동을 멈추는데, 화면에서
 * 넣은 오타가 그렇게 기동을 막으면 그 값을 되돌릴 화면에 닿을 수 없다. 그래서 {@code LlmConfig} 가 화면에서 고친 LOCAL 프로바이더를
 * 먼저 혼자 검증하고, 실패하면 {@link #fallBackAtStartup} 으로 properties 정의로 되돌린다(화면에서 추가한 것이면 등록하지 않는다).
 * 저장할 때도 같은 확인을 미리 한다 — 서버가 답했는데 모델이 없으면 저장하지 않는다.
 *
 * <p><b>API 키는 받지 않는다</b> — 키는 환경변수(.env)로만. 그 대가로 화면에서 추가하는 프로바이더는 키가 필요 없는 LOCAL 만이고,
 * 키가 있는 프로바이더의 주소를 <b>다른 호스트로</b> 바꾸는 것은 막는다(키가 새 호스트로 나간다).
 *
 * <p>이름이 키다 — 같은 이름의 프로바이더는 한 논리 모델이라({@code ProviderToggle}) 편집도 함께 받는다.
 */
@Service
public class ProviderCatalog {

    private static final Logger log = LoggerFactory.getLogger(ProviderCatalog.class);

    /** 화면에서 만드는 프로바이더의 이름 — 로그·감사·URL 파라미터에 그대로 실리므로 좁게. */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,39}");
    static final int MIN_PRIORITY = 0;
    static final int MAX_PRIORITY = 99;
    /** 수동 컨텍스트의 범위 — 아래는 어떤 프롬프트도 들어가지 않는 크기, 위는 오타를 거르는 상한. */
    static final int MIN_CONTEXT = 1_024;
    static final int MAX_CONTEXT = 10_000_000;
    static final int MAX_MODEL_LENGTH = 200;
    static final List<String> ROLES = List.of("LOCAL", "NORMAL", "PREMIUM");
    static final List<String> TYPES = Arrays.stream(TaskType.values()).map(Enum::name).toList();

    /** 다음 기동에 바뀌는 칸의 이름 — 화면 문구 키({@code settings.provider.field.<이름>})와 같다. */
    static final String F_BASE_URL = "base-url";
    static final String F_MODEL = "model";
    static final String F_TYPE = "type";
    static final String F_ROLE = "role";
    static final String F_PRIORITY = "priority";

    private final ProviderOverrideRepository repo;
    private final AppProperties props;
    private final ProviderContextWindows contextWindows;
    private final AuditLogger audit;
    /** 저장 전 새 주소·모델 확인 — 테스트가 바꿔 끼운다. */
    private final Function<AppProperties.ProviderConfig, ProviderConnectivity.Result> checker;
    /** 수동 컨텍스트를 비웠을 때 다시 물어보기 — 테스트가 바꿔 끼운다. */
    private final Function<AppProperties.ProviderConfig, Optional<Integer>> contextProber;

    /** DB 의 현재 상태 — 저장할 때마다 바뀐다. */
    private final Map<String, ProviderOverride> stored = new ConcurrentHashMap<>();
    /** 지금 돌고 있는 목록을 만든 편집 — 기동 때 굳고, 기동 검증의 되돌림만 바꾼다. */
    private volatile Map<String, ProviderOverride> applied = Map.of();
    /** properties 에서 온 목록(기동 때). */
    private volatile List<AppProperties.ProviderConfig> base = List.of();
    /** 지금 돌고 있는 목록 = base + applied. */
    private volatile List<AppProperties.ProviderConfig> running = List.of();
    /** 지난 기동에서 화면 값으로 검증에 실패한 프로바이더 → 사유. */
    private final Map<String, String> startupFailures = new ConcurrentHashMap<>();

    @Autowired
    public ProviderCatalog(ProviderOverrideRepository repo, AppProperties props,
                           ProviderContextWindows contextWindows, AuditLogger audit) {
        this(repo, props, contextWindows, audit, ProviderConnectivity::checkNow,
                cfg -> ContextWindowProbe.probe(cfg.apiBase(), cfg.model(),
                        props.llmSafe().connectTimeoutSeconds(), props.llmSafe().readTimeoutSeconds()));
    }

    /** 테스트용 — 실제로 서버에 묻는 두 함수를 바꿔 끼운다. */
    ProviderCatalog(ProviderOverrideRepository repo, AppProperties props, ProviderContextWindows contextWindows,
                    AuditLogger audit, Function<AppProperties.ProviderConfig, ProviderConnectivity.Result> checker,
                    Function<AppProperties.ProviderConfig, Optional<Integer>> contextProber) {
        this.repo = repo;
        this.props = props;
        this.contextWindows = contextWindows;
        this.audit = audit;
        this.checker = checker;
        this.contextProber = contextProber;
    }

    @PostConstruct
    void init() {
        // 묶기 전에 읽어야 properties 목록 그대로다(SettingsService.init() 이 오버라이드 전 값을 먼저 읽는 것과 같은 순서).
        base = props.llmSafe().providers();
        stored.putAll(repo.findAll());
        applied = Map.copyOf(stored);
        running = ProviderOverlay.apply(base, applied);
        List<AppProperties.ProviderConfig> source = base;
        AppProperties.bindProviderOverlay(raw -> raw == source ? running : ProviderOverlay.apply(raw, applied));
        if (!stored.isEmpty()) {
            log.info("[PROVIDERS] /settings 에서 고친 프로바이더 {}개를 application.properties 정의 위에 얹었다: {}",
                    stored.size(), stored.keySet());
        }
    }

    @PreDestroy
    void shutdown() {
        AppProperties.unbindProviderOverlay();
    }

    // ── 기동(LlmConfig) ──────────────────────────────────────────────────────

    /** 기동 때 혼자 먼저 검증할 프로바이더 — 화면에서 재기동 칸을 고친 LOCAL(추가한 것 포함). */
    public List<AppProperties.ProviderConfig> editedLocalProviders() {
        return running.stream()
                .filter(c -> c.isLocal() && c.isEnabled())
                .filter(c -> {
                    ProviderOverride o = applied.get(c.name());
                    return o != null && o.changesRestartFields();
                })
                .toList();
    }

    /**
     * 화면 값으로 기동 검증(G3)에 실패했다 — 그 프로바이더를 properties 정의로 되돌린다. 화면에서 추가한 것이면 이번 기동에는
     * 등록하지 않는다. 수동 컨텍스트는 실패와 무관하므로 남긴다. 저장된 편집은 지우지 않는다 — 화면에서 사유를 보고 고치거나
     * 되돌리라고.
     */
    public synchronized void fallBackAtStartup(String name, String reason) {
        ProviderOverride o = applied.get(name);
        if (o == null) return;
        Map<String, ProviderOverride> next = new HashMap<>(applied);
        if (inBase(name) && o.contextSize() != null) {
            next.put(name, o.contextOnly());
        } else {
            next.remove(name);
        }
        applied = Map.copyOf(next);
        running = ProviderOverlay.apply(base, applied);
        startupFailures.put(name, reason == null ? "-" : reason);
    }

    /**
     * 이 프로바이더에 화면에서 넣은 컨텍스트가 있는가 — 기동 때 {@code LlmConfig} 가 그 값을 선언으로 읽지 않고, 탐지를 먼저 해 본 뒤
     * 실패했을 때만 {@code MANUAL} 로 쓰려고(병합된 설정의 {@code contextSize} 에 섞여 온다).
     */
    public boolean hasManualContext(String name) {
        ProviderOverride o = applied.get(name);
        return o != null && o.contextSize() != null;
    }

    // ── 화면 ────────────────────────────────────────────────────────────────

    /** 표의 한 행에 붙일 편집 상태. */
    public ProviderEdit editOf(String name) {
        ProviderOverride s = stored.get(name);
        ProviderEdit.Origin origin = s == null ? ProviderEdit.Origin.PROPERTIES
                : s.added() ? ProviderEdit.Origin.ADDED : ProviderEdit.Origin.EDITED;
        Optional<AppProperties.ProviderConfig> now = find(running, name);
        Optional<AppProperties.ProviderConfig> next = find(nextList(), name);
        ProviderEdit.Pending pending;
        List<String> fields = List.of();
        if (now.isPresent() && next.isEmpty()) {
            pending = ProviderEdit.Pending.DELETE;
        } else if (now.isEmpty() && next.isPresent()) {
            pending = ProviderEdit.Pending.ADD;
        } else if (now.isPresent()) {
            fields = changedFields(now.get(), next.get());
            pending = fields.isEmpty() ? ProviderEdit.Pending.NONE : ProviderEdit.Pending.CHANGE;
        } else {
            pending = ProviderEdit.Pending.NONE;
        }
        return new ProviderEdit(origin, pending, fields, startupFailures.get(name),
                s == null ? null : s.contextSize());
    }

    /** 재기동하면 추가될, 아직 돌고 있지 않은 프로바이더(표의 맨 아래에 흐리게 그린다). */
    public List<AppProperties.ProviderConfig> pendingAdditions() {
        List<AppProperties.ProviderConfig> next = nextList();
        return next.stream().filter(c -> find(running, c.name()).isEmpty()).toList();
    }

    /** 편집 폼의 처음 값 — 다음 기동에 쓰일 정의(저장된 편집 포함). 새 프로바이더면 빈 폼. */
    public Edit formFor(String name) {
        if (name == null || name.isBlank()) {
            return new Edit(null, null, null, TaskType.BOTH.name(), "LOCAL", 1, null, true);
        }
        AppProperties.ProviderConfig cfg = find(nextList(), name)
                .or(() -> find(running, name))
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 프로바이더입니다: " + name));
        ProviderOverride s = stored.get(name);
        Integer context = s != null && s.contextSize() != null ? s.contextSize()
                : find(base, name).map(AppProperties.ProviderConfig::declaredContextSize).orElse(null);
        return new Edit(cfg.name(), cfg.baseUrl(), cfg.model(), upper(cfg.type(), "BOTH"), upper(cfg.role(), "NORMAL"),
                cfg.priority(), context, false);
    }

    static final String LOCK_DECLARED = "declared";
    static final String LOCK_PROBED = "probed";

    /**
     * 컨텍스트를 화면에서 넣을 수 없는 이유 — {@code context-size} 선언이 있거나({@link #LOCK_DECLARED}), 지금 탐지값이 있다
     * ({@link #LOCK_PROBED}). 수동 입력은 <b>탐지가 안 될 때만</b> 쓰는 대체값이라서다 — 탐지가 되면 그 값이 맞고, 나중에 탐지가
     * 성공하면 수동 값을 덮는다(재탐지 버튼·자동 재탐지·기동). 넣을 수 있으면 빈 값.
     */
    public Optional<String> contextLock(String name) {
        if (name == null) return Optional.empty();
        if (find(base, name).map(AppProperties.ProviderConfig::declaredContextSize).isPresent()) {
            return Optional.of(LOCK_DECLARED);
        }
        return contextWindows.find(name)
                .filter(w -> w.source() == ProviderContextWindows.Source.PROBED)
                .map(w -> LOCK_PROBED);
    }

    /** properties 의 컨텍스트 선언 — 폼의 자리표시자("비우면 이 값")에 쓴다. */
    public Optional<Integer> declaredContext(String name) {
        return find(base, name).map(AppProperties.ProviderConfig::declaredContextSize);
    }

    /**
     * 이 프로바이더에 진짜 API 키가 있는가 — 폼이 "주소의 호스트는 바꿀 수 없다"를 미리 알리고, 저장이 그 규칙을 건다. LOCAL 슬롯의
     * 기본값 {@code no-key} 는 키가 아니다 — 키가 필요 없는 서버에 앱이 채워 넣는 자리표시자(`LlmConfig`·`EmbeddingBeanConfig` 와
     * 같은 값)라, 그것까지 키로 보면 로컬 서버의 주소를 옮기는 가장 흔한 편집이 막힌다.
     */
    public boolean hasApiKey(String name) {
        return find(base, name).map(c -> isRealKey(c.apiKey())).orElse(false);
    }

    /** 앱이 키 없는 서버에 채우는 자리표시자 — {@code LlmConfig}·{@code EmbeddingBeanConfig}·{@code ProviderConnectivity} 와 같다. */
    static final String NO_KEY_PLACEHOLDER = "no-key";

    static boolean isRealKey(String key) {
        return key != null && !key.isBlank() && !NO_KEY_PLACEHOLDER.equalsIgnoreCase(key.trim());
    }

    // ── 쓰기 ────────────────────────────────────────────────────────────────

    /**
     * 편집 폼 한 장.
     *
     * @param create 새 프로바이더인가 — {@code name} 을 이때만 받는다
     */
    public record Edit(String name, String baseUrl, String model, String type, String role, Integer priority,
                       Integer contextSize, boolean create) {}

    /**
     * 저장 결과 — 화면에 보일 안내(메시지 키)와 컨텍스트가 바뀌었는지(생각 수준 카드를 다시 계산하게).
     *
     * @param notes 메시지 키 — {@code settings.provider.note.*}
     */
    public record SaveResult(String name, List<String> notes, boolean contextChanged) {}

    static final String NOTE_SAVED = "settings.provider.note.saved";
    static final String NOTE_RESTART = "settings.provider.note.restart";
    static final String NOTE_CONTEXT = "settings.provider.note.context";
    static final String NOTE_UNVERIFIED = "settings.provider.note.unverified";
    static final String NOTE_NO_KEY = "settings.provider.note.no-key";
    static final String NOTE_NOTHING = "settings.provider.note.nothing";
    static final String NOTE_DELETED = "settings.provider.note.deleted";
    static final String NOTE_REVERTED = "settings.provider.note.reverted";

    /** 저장 — 컨텍스트는 즉시, 나머지는 다음 기동에. 검증 실패는 {@link IllegalArgumentException}(폼에 사유를 그린다). */
    public synchronized SaveResult save(Edit e) {
        String name = trim(e.name());
        String baseUrl = trim(e.baseUrl());
        String model = trim(e.model());
        String type = upper(trim(e.type()), null);
        String role = upper(trim(e.role()), null);
        Integer priority = e.priority();
        Integer context = e.contextSize();

        if (name == null) throw new IllegalArgumentException("이름을 넣으세요.");
        boolean known = inBase(name) || stored.containsKey(name);
        if (e.create()) {
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("이름은 영문·숫자로 시작하는 40자 이하의 영문·숫자·점·밑줄·하이픈이어야 합니다: " + name);
            }
            if (known || find(running, name).isPresent()) {
                throw new IllegalArgumentException("이미 있는 이름입니다: " + name);
            }
        } else if (!known) {
            throw new IllegalArgumentException("알 수 없는 프로바이더입니다: " + name);
        }
        if (stored.containsKey(name) && stored.get(name).deleted()) {
            throw new IllegalArgumentException("삭제 대기 중인 프로바이더입니다 — 먼저 [되돌리기] 로 삭제를 취소하세요: " + name);
        }
        validateUrl(baseUrl);
        if (model == null) throw new IllegalArgumentException("모델을 넣으세요.");
        if (model.length() > MAX_MODEL_LENGTH || model.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("모델 이름에는 공백을 넣을 수 없고 " + MAX_MODEL_LENGTH + "자 이하여야 합니다.");
        }
        if (type == null || !TYPES.contains(type)) {
            throw new IllegalArgumentException("type 은 " + String.join(" / ", TYPES) + " 중 하나여야 합니다.");
        }
        if (role == null || !ROLES.contains(role)) {
            throw new IllegalArgumentException("role 은 " + String.join(" / ", ROLES) + " 중 하나여야 합니다.");
        }
        if (priority == null || priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
            throw new IllegalArgumentException("우선순위는 " + MIN_PRIORITY + "~" + MAX_PRIORITY + " 이어야 합니다.");
        }
        if (context != null && (context < MIN_CONTEXT || context > MAX_CONTEXT)) {
            throw new IllegalArgumentException("컨텍스트는 %,d~%,d 토큰이어야 합니다(비우면 선언값 또는 자동 탐지).".formatted(MIN_CONTEXT, MAX_CONTEXT));
        }
        Optional<AppProperties.ProviderConfig> fromProps = find(base, name);
        // 수동 컨텍스트는 탐지가 안 될 때의 대체값이다 — 선언됐거나 지금 탐지값이 있으면 받지 않는다. 그때 칸은 잠겨 비어 오므로
        // (disabled 는 전송되지 않는다) 빈 값은 "지우기"가 아니라 "그대로"다 — 탐지 실패 때 쓸 대체값을 남긴다.
        Integer storedCtx = stored.containsKey(name) ? stored.get(name).contextSize() : null;
        Optional<String> lock = e.create() ? Optional.empty() : contextLock(name);
        if (lock.isPresent()) {
            if (context == null) {
                context = storedCtx;
            } else if (!Objects.equals(context, storedCtx)
                    && !Objects.equals(context, fromProps.map(AppProperties.ProviderConfig::declaredContextSize).orElse(null))) {
                throw new IllegalArgumentException(LOCK_DECLARED.equals(lock.get())
                        ? "context-size 로 선언된 프로바이더라 컨텍스트를 화면에서 넣을 수 없습니다 — .env 에서 고치세요."
                        : "컨텍스트가 자동 탐지되고 있어 수동으로 넣을 수 없습니다 — 수동 입력은 탐지가 안 될 때만 씁니다.");
            }
        }
        boolean added = fromProps.isEmpty();
        if (added && !"LOCAL".equals(role)) {
            throw new IllegalArgumentException("화면에서 추가하는 프로바이더는 LOCAL 만 됩니다 — API 키를 화면에서 받지 않습니다"
                    + "(원격 프로바이더는 .env 의 app.llm.providers[N] 로 추가하세요).");
        }
        if (fromProps.isPresent() && hasApiKey(name) && !sameHost(fromProps.get().baseUrl(), baseUrl)) {
            throw new IllegalArgumentException("API 키가 있는 프로바이더의 주소를 다른 호스트로 바꿀 수 없습니다 — 키가 새 주소로 나갑니다. "
                    + "호스트·포트를 바꾸려면 .env 에서 고치세요(같은 호스트·포트 안에서 경로만 바꾸는 것은 됩니다).");
        }

        ProviderOverride o = added
                ? new ProviderOverride(name, baseUrl, model, type, role, priority, context, true, false)
                : relativeToBase(fromProps.get(), baseUrl, model, type, role, priority, context);
        Map<String, ProviderOverride> after = new HashMap<>(stored);
        if (isEmpty(o)) after.remove(name); else after.put(name, o);
        requireAProviderLeft(after);

        List<String> notes = new ArrayList<>();
        AppProperties.ProviderConfig nextCfg = find(ProviderOverlay.apply(base, after), name).orElseThrow();
        AppProperties.ProviderConfig nowCfg = find(running, name).orElse(null);
        boolean restartFieldsChange = nowCfg == null || !changedFields(nowCfg, nextCfg).isEmpty();
        if (nextCfg.isLocal() && nextCfg.isEnabled() && restartFieldsChange) {
            ProviderConnectivity.Result r = checker.apply(nextCfg);
            if (r.reachable() && Boolean.FALSE.equals(r.modelListed())) {
                throw new IllegalArgumentException("서버(%s)의 모델 목록에 '%s' 가 없습니다 — 이대로면 다음 기동의 모델 검증에 실패합니다."
                        .formatted(nextCfg.baseUrl(), model));
            }
            if (!r.reachable()) notes.add(NOTE_UNVERIFIED);
        }
        if (!nextCfg.isEnabled()) notes.add(NOTE_NO_KEY);

        Integer contextBefore = stored.containsKey(name) ? stored.get(name).contextSize() : null;
        if (isEmpty(o)) {
            repo.delete(name);
            stored.remove(name);
        } else {
            repo.upsert(o);
            stored.put(name, o);
        }
        boolean contextChanged = !Objects.equals(contextBefore, o.contextSize());
        if (contextChanged && find(running, name).isPresent()) applyContextNow(name, o.contextSize());
        if (contextChanged) notes.add(NOTE_CONTEXT);
        if (editOf(name).pending() != ProviderEdit.Pending.NONE) notes.add(0, NOTE_RESTART);
        notes.add(0, notes.isEmpty() ? NOTE_NOTHING : NOTE_SAVED);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("create", e.create());
        details.put("baseUrl", baseUrl);
        details.put("model", model);
        details.put("type", type);
        details.put("role", role);
        details.put("priority", priority);
        details.put("contextSize", context == null ? "-" : context);
        audit.log("settings.provider.save", name, details);
        return new SaveResult(name, List.copyOf(notes), contextChanged);
    }

    /**
     * 삭제 — properties 정의는 숨김으로 표시하고(재기동 뒤 등록하지 않는다), 아직 돌지 않는 추가분은 바로 지운다. 재기동 뒤
     * 등록될 프로바이더가 하나도 남지 않으면 거절한다.
     */
    public synchronized SaveResult delete(String name) {
        String n = trim(name);
        if (n == null || !(inBase(n) || stored.containsKey(n))) {
            throw new IllegalArgumentException("알 수 없는 프로바이더입니다: " + name);
        }
        ProviderOverride s = stored.get(n);
        boolean isRunning = find(running, n).isPresent();
        Map<String, ProviderOverride> after = new HashMap<>(stored);
        ProviderOverride marked = null;
        if (!inBase(n) && !isRunning) {
            after.remove(n);
        } else {
            marked = new ProviderOverride(n, null, null, null, null, null, s == null ? null : s.contextSize(),
                    s != null && s.added(), true);
            after.put(n, marked);
        }
        requireAProviderLeft(after);
        if (marked == null) {
            repo.delete(n);
            stored.remove(n);
        } else {
            repo.upsert(marked);
            stored.put(n, marked);
        }
        audit.log("settings.provider.delete", n, Map.of("pendingRestart", marked != null));
        return new SaveResult(n, List.of(NOTE_DELETED), false);
    }

    /** 되돌리기 — 화면에 남긴 것을 지운다. 컨텍스트는 즉시 properties 선언 또는 탐지로, 나머지는 다음 기동에. */
    public synchronized SaveResult revert(String name) {
        String n = trim(name);
        ProviderOverride s = n == null ? null : stored.get(n);
        if (s == null) throw new IllegalArgumentException("되돌릴 편집이 없습니다: " + name);
        Map<String, ProviderOverride> after = new HashMap<>(stored);
        after.remove(n);
        requireAProviderLeft(after);
        repo.delete(n);
        stored.remove(n);
        boolean contextChanged = s.contextSize() != null;
        if (contextChanged && find(running, n).isPresent()) applyContextNow(n, null);
        audit.log("settings.provider.revert", n, Map.of("hadContext", contextChanged));
        List<String> notes = new ArrayList<>(List.of(NOTE_REVERTED));
        if (editOf(n).pending() != ProviderEdit.Pending.NONE) notes.add(NOTE_RESTART);
        return new SaveResult(n, List.copyOf(notes), contextChanged);
    }

    // ── 내부 ────────────────────────────────────────────────────────────────

    /**
     * 컨텍스트를 지금 반영한다. 값이 있으면 수동 입력으로 기록하고, 비웠으면 properties 선언 → (LOCAL 이면) 지금 탐지 → 모름 순으로
     * 돌아간다.
     */
    private void applyContextNow(String name, Integer manual) {
        if (manual != null) {
            contextWindows.record(name, manual, ProviderContextWindows.Source.MANUAL);
            ProviderContextWindows.warnIfBelowSupportedMinimum(name, manual, ProviderContextWindows.Source.MANUAL, "수동 입력");
            return;
        }
        Optional<AppProperties.ProviderConfig> fromProps = find(base, name);
        Integer declared = fromProps.map(AppProperties.ProviderConfig::declaredContextSize).orElse(null);
        if (declared != null) {
            contextWindows.record(name, declared, ProviderContextWindows.Source.CONFIGURED);
            return;
        }
        contextWindows.forget(name);
        find(running, name)
                .filter(c -> c.isLocal() && c.isEnabled())
                .flatMap(contextProber)
                .ifPresent(t -> {
                    contextWindows.record(name, t, ProviderContextWindows.Source.PROBED);
                    ProviderContextWindows.warnIfBelowSupportedMinimum(name, t, ProviderContextWindows.Source.PROBED, "재탐지");
                });
    }

    /** properties 정의와 같은 칸은 비워 둔다 — 그래야 그 칸은 이후 properties 를 고쳐도 따라가고, 다 같으면 행 자체가 사라진다. */
    private static ProviderOverride relativeToBase(AppProperties.ProviderConfig cfg, String baseUrl, String model,
                                                   String type, String role, Integer priority, Integer context) {
        return new ProviderOverride(cfg.name(),
                Objects.equals(baseUrl, cfg.baseUrl()) ? null : baseUrl,
                Objects.equals(model, cfg.model()) ? null : model,
                type.equals(upper(cfg.type(), "BOTH")) ? null : type,
                role.equals(upper(cfg.role(), "NORMAL")) ? null : role,
                priority == cfg.priority() ? null : priority,
                Objects.equals(context, cfg.declaredContextSize()) ? null : context,
                false, false);
    }

    private static boolean isEmpty(ProviderOverride o) {
        return !o.changesRestartFields() && o.contextSize() == null;
    }

    /** 재기동 뒤 등록될 프로바이더가 하나는 남아야 한다 — LOCAL_ONLY 배포면 LOCAL 이. 없으면 기동이 실패한다. */
    private void requireAProviderLeft(Map<String, ProviderOverride> after) {
        boolean localOnly = "LOCAL_ONLY".equals(props.llmSafe().defaultRoutingMode());
        boolean any = ProviderOverlay.apply(base, after).stream()
                .anyMatch(c -> c.isEnabled() && (!localOnly || c.isLocal()));
        if (!any) {
            throw new IllegalArgumentException(localOnly
                    ? "LOCAL_ONLY 배포에서 등록될 LOCAL 프로바이더가 하나도 남지 않습니다 — 그러면 앱이 기동하지 못합니다."
                    : "등록될 프로바이더가 하나도 남지 않습니다 — 그러면 앱이 기동하지 못합니다.");
        }
    }

    /** 다음 기동에 쓰일 목록 — 지금 저장된 편집 전부(컨텍스트는 즉시 반영이라 비교에서 뺀다). */
    private List<AppProperties.ProviderConfig> nextList() {
        return ProviderOverlay.apply(base, Map.copyOf(stored));
    }

    static List<String> changedFields(AppProperties.ProviderConfig now, AppProperties.ProviderConfig next) {
        List<String> out = new ArrayList<>();
        if (!Objects.equals(now.baseUrl(), next.baseUrl())) out.add(F_BASE_URL);
        if (!Objects.equals(now.model(), next.model())) out.add(F_MODEL);
        if (!Objects.equals(upper(now.type(), "BOTH"), upper(next.type(), "BOTH"))) out.add(F_TYPE);
        if (!Objects.equals(upper(now.role(), "NORMAL"), upper(next.role(), "NORMAL"))) out.add(F_ROLE);
        if (now.priority() != next.priority()) out.add(F_PRIORITY);
        return out;
    }

    private boolean inBase(String name) {
        return find(base, name).isPresent();
    }

    private static Optional<AppProperties.ProviderConfig> find(List<AppProperties.ProviderConfig> list, String name) {
        return list.stream().filter(c -> name.equals(c.name())).findFirst();
    }

    private static void validateUrl(String url) {
        if (url == null) throw new IllegalArgumentException("접속 주소를 넣으세요.");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("접속 주소가 URL 이 아닙니다: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !Set.of("http", "https").contains(scheme.toLowerCase()) || uri.getHost() == null) {
            throw new IllegalArgumentException("접속 주소는 http(s)://호스트[:포트]/… 여야 합니다: " + url);
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("접속 주소에 계정 정보·쿼리·조각(#)을 넣을 수 없습니다: " + url);
        }
    }

    /** 키가 나가는 곳이 같은가 — 스킴·호스트·포트. 경로는 달라도 된다. */
    static boolean sameHost(String a, String b) {
        try {
            URI x = URI.create(a);
            URI y = URI.create(b);
            return Objects.equals(lower(x.getScheme()), lower(y.getScheme()))
                    && Objects.equals(lower(x.getHost()), lower(y.getHost()))
                    && effectivePort(x) == effectivePort(y);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static int effectivePort(URI u) {
        if (u.getPort() >= 0) return u.getPort();
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase();
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String upper(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim().toUpperCase();
    }
}
