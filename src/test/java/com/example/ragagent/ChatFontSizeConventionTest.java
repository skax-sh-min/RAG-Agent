package com.example.ragagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채팅 글자 크기 규약 — 채팅 렌더러는 글자 크기를 스스로 정하지 않는다.
 *
 * <p>채팅 화면의 글자 크기는 /settings 의 "화면 표시"(최소·작게·보통·크게)가 고른 변수 하나({@code --chat-font-size})와,
 * 그에 대한 비율(em) 단계 규칙({@code app.css} '채팅 글자 크기')이 전부 정한다. 렌더러가 인라인 {@code font-size} 를 주면
 * 그 요소는 설정을 따라오지 않고, 렌더러마다 값이 갈라진다 — 실제로 메타데이터 줄이 새로고침 전후로 0.72rem/0.68rem
 * 이었고, 스트리밍 경로는 배지·안내 줄을 0.72rem 인 줄 안에 넣어 12px→8.6px, 14px→10px 로 줄여 그리고 있었다.
 * 크기가 필요하면 app.css 에 단계 클래스를 쓰거나 만든다.
 */
class ChatFontSizeConventionTest {

    /** 채팅 말풍선을 그리는 곳 전부 — 서버 렌더러(기록 루프·재사용·HTMX 조각)와 스트리밍. */
    private static final List<Path> CHAT_RENDERERS = List.of(
            Path.of("src/main/resources/templates/chat.html"),
            Path.of("src/main/resources/templates/fragments/message-assistant.html"),
            Path.of("src/main/resources/templates/fragments/message-user.html"),
            Path.of("src/main/resources/templates/fragments/message-error.html"),
            Path.of("src/main/resources/static/js/chat-stream.js"));

    /** 인라인 크기의 모양들 — style 속성·문자열 안의 CSS 선언, DOM 스타일 대입. */
    private static final Pattern INLINE_FONT_SIZE =
            Pattern.compile("font-size\\s*:|\\.style\\.fontSize\\b|setProperty\\(\\s*['\"]font-size");

    @Test
    @DisplayName("채팅 렌더러에는 인라인 글자 크기가 없다 — 크기는 app.css '채팅 글자 크기'의 단계 규칙이 정한다")
    void chatRenderersCarryNoInlineFontSize() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : CHAT_RENDERERS) {
            assertThat(file).as("검사 대상 파일이 옮겨졌다면 이 목록도 고칠 것").exists();
            String code = withoutComments(Files.readString(file));
            Matcher m = INLINE_FONT_SIZE.matcher(code);
            while (m.find()) {
                violations.add(file + " — " + lineAt(code, m.start()));
            }
        }

        assertThat(violations)
                .as("채팅 렌더러에 인라인 글자 크기가 있다. app.css '채팅 글자 크기'의 단계 클래스를 쓰거나 만들 것 — "
                        + "인라인 크기는 /settings 의 글자 크기 설정을 따라오지 않는다")
                .isEmpty();
    }

    @Test
    @DisplayName("출처 미리보기 팝오버를 만드는 곳은 모두 source-preview-popover 를 단다 — 팝오버는 body 에 붙어 목록 밖이다")
    void everySourcePreviewPopoverOptsIntoTheChatFontSize() throws IOException {
        // 팝오버는 #chat-messages(.chat-font-scope) 밖에 붙으므로 이 클래스가 없으면 설정을 따라오지 않는다.
        // 만드는 곳이 둘(chat.html 의 재사용·기록 경로, chat-stream.js 의 스트리밍 경로)이라 한쪽만 빠지기 쉽다.
        for (Path file : List.of(CHAT_RENDERERS.get(0), CHAT_RENDERERS.get(4))) {
            String code = withoutComments(Files.readString(file));
            int created = count(code, "new bootstrap.Popover(");
            assertThat(created).as("%s 에서 팝오버를 만드는 곳을 찾지 못했다", file).isPositive();
            assertThat(count(code, "customClass: 'source-preview-popover'"))
                    .as("%s — 팝오버를 만드는 곳마다 customClass: 'source-preview-popover' 가 있어야 한다", file)
                    .isEqualTo(created);
        }
    }

    /**
     * HTML 주석, 블록 주석, 줄 주석을 지운다 — 옛 값을 설명하는 주석이 남아 있어서다. 줄 주석은 앞이 공백이나 줄 시작일
     * 때만 지운다(문자열 안 URL 의 {@code //} 를 건드리지 않도록).
     */
    static String withoutComments(String source) {
        return source
                .replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)(^|[ \\t])//.*$", "$1");
    }

    private static String lineAt(String text, int index) {
        int start = text.lastIndexOf('\n', index) + 1;
        int end = text.indexOf('\n', index);
        return text.substring(start, end < 0 ? text.length() : end).strip();
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) n++;
        return n;
    }
}
