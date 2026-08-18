package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AnswerServiceTest stubs MessageSource with a dummy "prompt" string, so nothing there exercises
 * the real prompt.answer.eval text. That text is where the environment-dependent-value contract
 * actually lives: paths, hosts, ports and env-var values legitimately differ between the machine a
 * document was written on and the machine the reader is using, so failing grounded on them alone
 * produced 미검증 badges (and retries) for answers that were in fact correct. The rule is a prompt
 * instruction, not code — nothing else in the build would notice if it were edited away — hence
 * this bundle-level guard.
 */
class AnswerEvalPromptTest {

    private static ResourceBundleMessageSource realMessageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        // 개발기의 시스템 로케일이 ko 라 Locale.ENGLISH 조회가 messages_en(없음) → 시스템 로케일
        // 번들(messages_ko)로 폴백된다. 그러면 영어 번들을 한 줄도 안 읽고 통과하므로 끈다.
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }

    private static String evalPrompt(Locale locale) {
        return realMessageSource().getMessage("prompt.answer.eval", null, locale);
    }

    @Test
    @DisplayName("실제 번들의 prompt.answer.eval 이 envNote 필드를 요구한다 (한/영)")
    void realBundle_asksForEnvNote() {
        assertThat(evalPrompt(Locale.KOREAN)).contains("envNote");
        assertThat(evalPrompt(Locale.ENGLISH)).contains("envNote");
    }

    @Test
    @DisplayName("실제 번들의 prompt.answer.eval 이 환경 의존 값만으로는 grounded=false 를 못 내게 막는다")
    void realBundle_forbidsGroundedFalseForEnvironmentValues() {
        String ko = evalPrompt(Locale.KOREAN);
        assertThat(ko).contains("환경 의존 값 예외");
        assertThat(ko).contains("grounded=false");
        assertThat(ko).contains("grounded=true");

        String en = evalPrompt(Locale.ENGLISH);
        assertThat(en).contains("Environment-dependent value exception");
        assertThat(en).contains("grounded=false");
        assertThat(en).contains("grounded=true");
    }

    @Test
    @DisplayName("실제 번들의 prompt.answer.eval 이 예외 대상 값 종류를 구체적으로 열거한다")
    void realBundle_enumeratesEnvironmentValueKinds() {
        String ko = evalPrompt(Locale.KOREAN);
        assertThat(ko).contains("경로");
        assertThat(ko).contains("포트");
        assertThat(ko).contains("환경변수");
        assertThat(ko).contains("IP");

        String en = evalPrompt(Locale.ENGLISH);
        assertThat(en).contains("paths");
        assertThat(en).contains("ports");
        assertThat(en).contains("environment-variable");
        assertThat(en).contains("IP");
    }

    @Test
    @DisplayName("예외는 값에만 적용되고 절차·동작이 문서와 다르면 여전히 grounded=false 임을 명시한다")
    void realBundle_keepsBehavioralMismatchUngrounded() {
        assertThat(evalPrompt(Locale.KOREAN)).contains("절차");
        assertThat(evalPrompt(Locale.ENGLISH)).contains("procedure");
    }

    @Test
    @DisplayName("실제 번들이 usedDocs 를 [Dn] 번호 기준으로 요구한다 (2단계 응답 참여도)")
    void realBundle_asksForUsedDocs() {
        // AnswerService.buildEvalExcerpts()가 발췌마다 붙이는 [D1],[D2]… 번호와 이 지시가 짝이다 —
        // 한쪽만 바뀌면 모델이 무엇을 세라는 건지 알 수 없게 되므로 두 로케일 모두 고정한다.
        assertThat(evalPrompt(Locale.KOREAN)).contains("usedDocs").contains("[D1]");
        assertThat(evalPrompt(Locale.ENGLISH)).contains("usedDocs").contains("[D1]");
    }

    @Test
    @DisplayName("usedDocs 가 판정에 영향을 주지 않는 advisory 임을 프롬프트가 명시한다")
    void realBundle_marksUsedDocsAdvisory() {
        // 이 한 줄이 빠지면 모델이 "인용할 게 없으니 sufficient=false" 식으로 판정을 흔들 수 있다.
        assertThat(evalPrompt(Locale.KOREAN)).contains("판정에 영향을 주지 않습니다");
        assertThat(evalPrompt(Locale.ENGLISH)).contains("does not affect any verdict");
    }
}
