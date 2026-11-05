package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.llm.ThinkingControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import com.openai.errors.OpenAIServiceException;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generates Korean text descriptions for images using a vision-capable LLM.
 * Routes via LlmRouter with VISION task type, falling back through LIGHT_BOTH → BOTH.
 * Returns a placeholder string when no vision provider is available.
 */
@Service
public class VisionDescriptionService {

    private static final Logger log = LoggerFactory.getLogger(VisionDescriptionService.class);
    private static final AtomicBoolean UNSUPPORTED_VISION_LOGGED = new AtomicBoolean(false);

    /**
     * 이미지 설명 프롬프트 — <b>형식 + 한두 문장</b>, 한 줄. 인덱싱({@code MarkdownCorrectionService})·지연 Vision·지식 제안 승인이
     * 모두 이것을 쓴다.
     *
     * <p>예전 프롬프트("2~3문장으로 간단히", 유형별 최대 3~4문장)는 소형 모델이 {@code "이 이미지는 소프트웨어 개발 환경에서 …를
     * 보여줍니다"} 같은 <b>어느 이미지에나 맞는 문장</b>을 쓰게 했다 — 실제 코퍼스의 설명 10개 중 8개가 그랬고, 그 문장이 청크의
     * 임베딩·BM25 입력과 답변 프롬프트에 그대로 들어가 검색을 흐리고 토큰만 먹었다. 그래서 ① 형식을 고정 어휘에서 먼저 고르게 해
     * (검색할 때 "표", "로그", "IDE 화면" 같은 단서가 된다) ② 설명은 이미지에서 <b>읽히는 구체적인 것</b>(이름·문구·수치·단계)만 한두
     * 문장으로 ③ 일반적인 문구를 명시적으로 금지한다. 예시는 형식 참고용이며 소형 모델이 베끼기 쉬워 베끼지 말라고 적는다.
     *
     * <p>출력은 {@code 형식: 설명} 한 줄이다. 대괄호·세로선·줄바꿈은 {@link #cleanCaption} 이 없앤다 — 설명은 {@code [이미지 설명: …]}
     * 안에, 때로는 표 셀 안에 들어가므로 {@code ]} 와 {@code |} 가 그 구조를 깨뜨린다.
     */
    public static final String CAPTION_PROMPT = """
            이미지를 보고 아래 형식의 **한 줄**만 출력하세요.
            형식: <형식>: <설명>

            <형식>은 다음 중 가장 알맞은 하나입니다:
            표, 다이어그램, 순서도, 구조도, 차트/그래프, IDE 화면, 터미널 화면, 웹/앱 화면, 소스코드, 로그, 문서 캡처, 풍경사진, 인물사진, 사진, 아이콘/로고, 기타

            <설명> 규칙:
            - 한두 문장, 100자 안팎. 문서를 검색할 때 단서가 될 정보만 적습니다.
            - 이미지에서 실제로 읽히는 구체적인 것만 적습니다: 제목, 클래스·메서드·파일명, 메뉴·버튼 이름, 오류 문구, 수치, 단계·구성 요소 이름과 그 연결 순서.
            - 소스코드·로그·표는 전체를 옮기지 말고 핵심 식별자나 열 이름 몇 개만 그대로 적습니다.
            - 금지: "이 이미지는 ~을 보여줍니다" 같은 서두, "소프트웨어 개발 환경", "다양한", "여러 가지" 같은 일반적·추상적 표현, 색·배치·스타일 묘사, 추측.
            - 읽히는 정보가 없는 장식 이미지·로고·아이콘이면 "아이콘/로고: 장식용"처럼 짧게 적습니다.
            - 후보나 선택지를 나열하지 말고 한 줄만 출력합니다. 대괄호와 세로선(|)은 쓰지 않습니다.

            예시(형식 참고용 — 내용을 베끼지 마세요):
            순서도: 로그인 요청 → 인증 서버 검증 → 세션 생성 → 메인 화면 이동
            IDE 화면: UserService.java 12행에서 NullPointerException 으로 디버거가 멈춘 상태
            표: 환경별(dev/stg/prd) DB 접속 URL 비교, 열은 구분·URL·계정
            """;

    /** {@link #describe} 가 실패했을 때 돌려주는 자리표시자인가 — 성공한 설명은 {@link #cleanCaption} 이 대괄호를 없애므로 {@code [} 로 시작하지 않는다. */
    public static boolean isPlaceholder(String description) {
        return description == null || description.isBlank() || description.startsWith("[이미지 설명");
    }

    /** 설명 한 줄의 글자 수 상한 — 넘으면 마지막 공백에서 자르고 말줄임표를 붙인다. */
    static final int MAX_CAPTION_CHARS = 300;

    /**
     * 모델의 응답을 {@code [이미지 설명: …]} 안에 넣어도 되는 한 줄로 정리한다: 첫 비어 있지 않은 줄, 감싼 따옴표·백틱·글머리 제거,
     * 공백 접기, {@code [ ]} → {@code ( )}, {@code |} → {@code /}, {@link #MAX_CAPTION_CHARS} 자 상한. 모델이 서두("이 이미지는 ")를
     * 붙이면 그것만 떼어 낸다 — 프롬프트가 금지하지만 작은 모델은 어긴다.
     */
    static String cleanCaption(String raw) {
        if (raw == null) return "";
        String line = raw.lines().map(String::strip).filter(s -> !s.isEmpty()).findFirst().orElse("");
        line = line.replaceFirst("^(?:[-*•]\\s+|\\d+[.)]\\s+)", "");
        if (line.length() >= 2 && ((line.startsWith("\"") && line.endsWith("\"")) || (line.startsWith("'") && line.endsWith("'"))
                || (line.startsWith("`") && line.endsWith("`")) || (line.startsWith("“") && line.endsWith("”")))) {
            line = line.substring(1, line.length() - 1).strip();
        }
        line = line.replaceFirst("^(?:이 )?(?:이미지|그림|사진)는\\s+", "");
        line = line.replace('[', '(').replace(']', ')').replace('|', '/').replaceAll("\\s+", " ").strip();
        if (line.length() > MAX_CAPTION_CHARS) {
            int cut = line.lastIndexOf(' ', MAX_CAPTION_CHARS);
            line = line.substring(0, cut > MAX_CAPTION_CHARS / 2 ? cut : MAX_CAPTION_CHARS).strip() + "…";
        }
        return line;
    }

    private final LlmRouter llmRouter;
    private final AppProperties props;

    public VisionDescriptionService(LlmRouter llmRouter, AppProperties props) {
        this.llmRouter = llmRouter;
        this.props = props;
    }

    /**
     * 출력 예약 — 설명은 한두 문장({@link #CAPTION_PROMPT})인데 상한을 싣지 않던 동안은 프로바이더에 기동 때 구워진
     * {@code max-tokens}(기본 12,000) 전체가 예약됐다 — 이미지 입력 토큰이 큰 비전 호출에서 그 예약이 이미지 자리를 먹는다
     * (PLAN §6.29 열린 항목 (d)). 값의 근거는 {@code ClassifierService.MAX_OUTPUT_TOKENS} 와 같다.
     */
    static final int MAX_OUTPUT_TOKENS = 2_048;

    /** Indexing/background temperature (hot-editable), read fresh per call — see AppProperties.LlmConfig. */
    private OpenAiChatOptions indexingOptions() {
        // §6.29 — 이미지 설명 사이트(app.llm.thinking.image-describe). 지연 Vision(LazyVisionService)도 여기로 온다.
        OpenAiChatOptions.Builder builder = ThinkingControl.mark(
                OpenAiChatOptions.builder().temperature(props.llmSafe().indexingTemperature()), ThinkingSite.IMAGE_DESCRIBE);
        int base = baseReservation(props.llmSafe().maxTokens()); // 0 = 프로바이더 기본값 유지
        if (base > 0) builder.maxTokens(base);
        return builder.build();
    }

    /**
     * 이 호출의 <b>기본</b> 출력 예약 — {@link #MAX_OUTPUT_TOKENS} 를 설정 상한으로 누른 것. 0 = 싣지 않는다(프로바이더
     * 기본값). 요청 옵션과 {@code /settings} 의 생각 수준 미리보기가 같은 함수를 지난다(§6.29 ⑦-바).
     */
    static int baseReservation(int configuredMaxTokens) {
        return configuredMaxTokens > 0 ? Math.min(configuredMaxTokens, MAX_OUTPUT_TOKENS) : 0;
    }

    public String describe(byte[] imageBytes, String mimeType) {
        return describe(imageBytes, mimeType, CAPTION_PROMPT);
    }

    public String describe(byte[] imageBytes, String mimeType, String prompt) {
        try {
            Media media = new Media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(imageBytes));
            UserMessage userMessage = UserMessage.builder().text(prompt).media(media).build();
            String response = llmRouter.executeWithTracking(ThinkingSite.IMAGE_DESCRIBE.taskType(),
                    ThinkingSite.IMAGE_DESCRIBE.fixedRoutingMode(),
                    model -> model.call(new Prompt(userMessage, indexingOptions())));
            return cleanCaption(response);
        } catch (LlmProviderExhaustedException e) {
            log.warn("No vision provider available: {}", e.getMessage());
            return "[이미지 설명 불가: Vision 프로바이더 미등록]";
        } catch (Exception e) {
            if (isVisionInputUnsupported(e)) {
                if (UNSUPPORTED_VISION_LOGGED.compareAndSet(false, true)) {
                    log.warn("Vision model does not support image input (mmproj missing or text-only model). "
                            + "Falling back to placeholder descriptions.");
                }
                return "[이미지 설명 불가: Vision 미지원 모델]";
            }
            if (e instanceof OpenAIServiceException o) {
                log.error("Vision description failed: HTTP {} body={}", o.statusCode(), compactBody(String.valueOf(o.body())));
            } else {
                log.error("Vision description failed", e);
            }
            return "[이미지 설명 생성 오류]";
        }
    }

    private boolean isVisionInputUnsupported(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lowered = msg.toLowerCase();
                if (lowered.contains("image input is not supported")
                        || lowered.contains("mmproj")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String compactBody(String body) {
        if (body == null || body.isBlank()) return "<empty>";
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 500 ? oneLine.substring(0, 500) + "...(truncated)" : oneLine;
    }
}
