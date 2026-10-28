package com.example.ragagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §6.31 — 질문 버블을 만드는 <b>클라이언트</b> 렌더러 셋(스트리밍 · 재사용 · HTMX 낙관적 버블)이 모두 이력 제외 표시를 단다.
 *
 * <p>JS 테스트 하네스가 없어 소스를 읽는다({@link ChatFontSizeConventionTest} 선례). 서버 렌더(기록 루프)는
 * {@code ChatControllerHtmxTest} 가 실제 HTML 로 본다. 렌더러가 넷이라 하나만 빠지기 쉽고, 빠지면 그 경로로 보낸 질문만
 * 새로고침 전까지 표시 없이 보인다 — 화면 어디에도 오류가 없다.
 */
class HistoryExclusionRendererConventionTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("표시의 모양은 base.html 한 곳(bubbleHistoryMark)이고, 클라이언트 렌더러 셋이 그것을 부른다")
    void everyClientBubbleRendererCallsTheSharedMark() throws IOException {
        assertThat(read("src/main/resources/templates/layout/base.html")).contains("function bubbleHistoryMark(");

        String stream = read("src/main/resources/static/js/chat-stream.js");
        assertThat(stream).as("스트리밍 버블").contains("${bubbleHistoryMark(excludeHistory)}");
        assertThat(stream).as("스트리밍 버블이 폼의 값을 받는다").contains("formData.get('excludeHistory')");

        String chat = read("src/main/resources/templates/chat.html");
        assertThat(chat).as("재사용 버블").contains("${bubbleHistoryMark(excludeHistory)}")
                .contains("data.excludeHistory === true");
        assertThat(chat).as("HTMX 낙관적 버블").contains("${bubbleHistoryMark(excludeHistoryInput.value)}");
        assertThat(chat).as("기록 루프(서버 렌더)").contains("th:if=\"${turn.excludeHistory}\"");
    }
}
