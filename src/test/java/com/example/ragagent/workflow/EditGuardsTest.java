package com.example.ragagent.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 지시문이 있어야 하는 가드(G3·G4·G10)와 의도 해석의 근거 검사 — AGENT_MCP_DESIGN §10.5·§10.6. */
class EditGuardsTest {

    // ── G10 ──

    @Test
    @DisplayName("G10 — 표현을 주변 글에 맞춘 문장은 통과(기준 문장의 식별자·숫자·부정 표현을 지켰다)")
    void sentenceThatKeepsTheFactsPasses() {
        String ref = "호출 전에 init() 이 끝났는지 30초 안에 확인해야 한다";
        assertThat(EditGuards.sentence("B 를 호출하기 전에 init() 이 30초 안에 끝났는지 확인하세요.", ref, "B 는 init() 뒤에 호출한다.", "B")).isNull();
    }

    @Test
    @DisplayName("G10 — 기준 문장의 값이 빠지거나, 없는 숫자·식별자를 넣거나, 부정이 뒤집히면 거절")
    void sentenceThatChangesFactsIsRejected() {
        String ref = "A 기능은 더 이상 사용되지 않습니다. 대신 B_API 를 사용하세요.";
        assertThat(EditGuards.sentence("A 기능은 더 이상 사용되지 않습니다. 대신 새 API 를 쓰세요.", ref, "", "A 기능")).contains("B_API");
        assertThat(EditGuards.sentence("A 기능은 v2.1 부터 사용되지 않습니다. 대신 B_API 를 사용하세요.", ref, "", "A 기능")).contains("2.1");
        assertThat(EditGuards.sentence("A 기능은 계속 쓸 수 있고 B_API 를 함께 쓰세요.", ref, "", "A 기능")).contains("부정");
        assertThat(EditGuards.sentence("A 기능은 보안 취약점 때문에 성능 문제로 법적 이유로 더 이상 사용되지 않습니다. 대신 B_API 를 사용하세요.",
                ref, "", "A 기능")).contains("없는 말");
    }

    @Test
    @DisplayName("G10 — 길이·줄바꿈·표지가 섞이면 거절")
    void sentenceShape() {
        String ref = "A 기능은 더 이상 사용되지 않습니다.";
        assertThat(EditGuards.sentence("A 는 끝.", ref, "", "A")).contains("길이");
        assertThat(EditGuards.sentence("A 기능은\n더 이상 사용되지 않습니다.", ref, "", "A")).contains("줄바꿈");
        assertThat(EditGuards.sentence("A 기능은 더 이상 사용되지 않습니다 [이미지: x]", ref, "", "A")).contains("표지");
        assertThat(EditGuards.sentence(" ", ref, "", "A")).contains("비었다");
    }

    // ── G3 · G4 ──

    @Test
    @DisplayName("G3 — 인용이나 그 섹션 제목에 대상이 있어야 지우기·바꾸기를 받는다")
    void relevance() {
        String text = "## B 기능\n\n호출 순서를 지킨다.\n\n## C 기능\n\nC 는 따로 쓴다.";
        assertThat(EditGuards.relevance(text, AnchoredEdit.Operation.delete("호출 순서를 지킨다.", null), "B 기능")).isNull();
        assertThat(EditGuards.relevance(text, AnchoredEdit.Operation.delete("C 는 따로 쓴다.", null), "B 기능")).contains("관련된 구간이 아니다");
        assertThat(EditGuards.relevance(text, AnchoredEdit.Operation.deleteSection("## B 기능"), "B 기능")).isNull();
        // 단어 경계 — ABC 는 B 의 일부가 아니다
        assertThat(EditGuards.relevance("ABC 를 쓴다.", AnchoredEdit.Operation.delete("ABC 를 쓴다.", null), "B")).isNotNull();
    }

    @Test
    @DisplayName("청크 전체 삭제 — 위치 표시용 상위 제목 아래의 대상 섹션이면 받는다(6c 사전 평가의 readLines·closeQuietly 모양)")
    void wholeChunkUnderABreadcrumbHeading() {
        String text = "## 26. 클래스: nexcore.framework.core.util.FileUtils\n### 26.46 메서드: `readLines(java.io.File)`\n\n"
                + "**패키지:** `nexcore.framework.core.util`\n\n```java\n@Deprecated public static List readLines(File f)\n# 펜스 안은 제목이 아니다\n```\n\n"
                + "#### 26.46.1 반환값\n\n줄 목록.";
        assertThat(EditGuards.wholeChunkBelongsTo(text, "readLines")).isTrue();
        assertThat(EditGuards.wholeChunkBelongsTo("## B 기능\n\nB 는 이렇게 쓴다.", "B 기능")).isTrue();
    }

    @Test
    @DisplayName("청크 전체 삭제 — 다른 섹션의 본문이 섞였거나 첫 제목 위에 이어진 글이 있거나 대상 제목이 없으면 거절")
    void wholeChunkWithForeignBodyIsRejected() {
        // 앞 메서드(getRuntimeMode)의 본문 + 대상 메서드 — 통째로 지우면 앞 메서드 설명까지 사라진다
        String mixed = "## 1. 클래스: BaseUtils\n### 1.4 메서드: `getRuntimeMode()`\n\n실행 모드를 돌려준다.\n\n"
                + "### 1.5 메서드: `isSingleJvmBatch()`\n\n단일 JVM 배치인지.";
        assertThat(EditGuards.wholeChunkBelongsTo(mixed, "isSingleJvmBatch")).isFalse();
        // 앞 섹션에서 이어진 글이 제목보다 먼저 온다
        assertThat(EditGuards.wholeChunkBelongsTo("앞 섹션의 꼬리.\n\n## B 기능\n\nB 설명.", "B")).isFalse();
        // 상위 제목에만 대상이 있고 본문은 대상과 무관한 형제 섹션 — 상위 제목의 하위라면 받는다
        assertThat(EditGuards.wholeChunkBelongsTo("## B 기능\n### 설정\n\n값을 넣는다.", "B")).isTrue();
        assertThat(EditGuards.wholeChunkBelongsTo("## 26. 클래스: FileUtils\n### 26.1 메서드: `copy()`\n\n복사.", "readLines")).isFalse();
        assertThat(EditGuards.wholeChunkBelongsTo("## B 기능", "B")).isFalse();   // 본문 없음
        // 단어 경계 — readLinesAll 제목은 readLines 의 섹션이 아니다
        assertThat(EditGuards.wholeChunkBelongsTo("### 메서드: `readLinesAll()`\n\n설명.", "readLines")).isFalse();
    }

    @Test
    @DisplayName("G4 — 새 숫자는 원문·지시문에 있어야 하고, 어디에도 없는 낱말은 하나까지")
    void newFacts() {
        String chunk = "A 는 30초 동안 기다린다.";
        assertThat(EditGuards.newFacts(chunk, "A 는 60초 동안 기다린다.", "30초가 아니라 60초다")).isNull();
        assertThat(EditGuards.newFacts(chunk, "A 는 90초 동안 기다린다.", "30초가 아니라 60초다")).contains("90");
        assertThat(EditGuards.newFacts(chunk, "A 는 서버 부하 상황에서 자동으로 재시도한다.", "30초가 아니라 60초다")).contains("없는 말");
    }

    // ── EditIntent ──

    @Test
    @DisplayName("해석된 의도 — 값은 지시문의 부분 문자열이어야 하고, 유형별 필수 값이 있어야 한다")
    void intentGrounding() {
        String instr = "B 기능과 관련하여 호출 전에 초기화해야 한다는 주의 사항을 추가해";
        assertThat(new EditIntent("add_note", "B 기능", "호출 전에 초기화해야 한다", null, null, null).groundingProblem(instr)).isNull();
        assertThat(new EditIntent("add_note", "B 기능", "반드시 재시작하라", null, null, null).groundingProblem(instr)).contains("지시문에 없습니다");
        assertThat(new EditIntent("add_note", "B 기능", null, null, null, null).shapeProblem()).contains("주의 사항");
        assertThat(new EditIntent("unclear", "B", null, null, null, null).shapeProblem()).contains("유형");
        assertThat(new EditIntent("replace", null, null, null, "A", "A").shapeProblem()).contains("같습니다");
    }

    @Test
    @DisplayName("해석 응답 읽기 — JSON 이 아니면 예외, 저장 모양은 왕복한다")
    void intentParseAndJson() {
        assertThatThrownBy(() -> EditIntent.parse("모르겠습니다")).isInstanceOf(IllegalArgumentException.class);
        EditIntent i = EditIntent.parse("결과: {\"type\":\"Deprecate\",\"subject\":\" A 기능 \",\"replacement\":\"B\"}");
        assertThat(i.type()).isEqualTo("deprecate");
        assertThat(i.subject()).isEqualTo("A 기능");
        EditIntent back = EditIntent.fromJson(i.toJson("nl"));
        assertThat(back).isEqualTo(i);
        assertThat(i.toJson("nl")).contains("\"mode\":\"nl\"");
    }
}
