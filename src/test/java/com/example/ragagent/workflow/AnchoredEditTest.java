package com.example.ragagent.workflow;

import com.example.ragagent.workflow.AnchoredEdit.Operation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 원문 인용에 고정된 편집(AGENT_MCP_DESIGN §10.4·§10.5) — 인용 찾기(G1), 보호 구간(G2), 크기(G5), 불변식(G6), 중복·무변화(G7). */
class AnchoredEditTest {

    private static final String DOC = """
            # A 기능

            A 기능은 요청을 30초 동안 기다린다.
            설정 키는 a.timeout 이다.

            [이미지: images/x/1.png]
            [이미지 설명: A 화면]

            ## B 기능

            B 는 A 를 감싼다.

            ```java
            a.call();
            ```

            # C 기능

            C 는 따로 쓴다.""";

    @Test
    @DisplayName("바꾸기 — 인용이 한 곳이면 그 구간만 바뀐다")
    void replaceChangesOnlyTheQuotedSpan() {
        AnchoredEdit.Result r = AnchoredEdit.apply(DOC, List.of(Operation.replace("30초 동안", "60초 동안")));

        assertThat(r.usable()).isTrue();
        assertThat(r.text()).isEqualTo(DOC.replace("30초 동안", "60초 동안"));
        assertThat(r.rejected()).isEmpty();
    }

    @Test
    @DisplayName("인용은 공백이 달라도(줄바꿈·연속 공백) 원래 자리로 되짚는다")
    void whitespaceNormalisedQuoteMapsBack() {
        AnchoredEdit.Result r = AnchoredEdit.apply(DOC, List.of(Operation.replace("기다린다.   설정 키는", "기다린다.\n설정 키(바뀜)는")));

        assertThat(r.usable()).isTrue();
        assertThat(r.text()).contains("기다린다.\n설정 키(바뀜)는 a.timeout");
    }

    @Test
    @DisplayName("인용이 없거나 여러 곳이면 그 작업만 버리고 사유를 남긴다(G1)")
    void missingOrAmbiguousQuoteIsRejected() {
        AnchoredEdit.Result r = AnchoredEdit.apply(DOC, List.of(
                Operation.replace("없는 문장", "x"),
                Operation.replace("기능", "모듈"),
                Operation.replace("C 는 따로 쓴다.", "C 는 따로 쓰지 않는다.")));

        assertThat(r.rejected()).extracting(AnchoredEdit.Rejected::index).containsExactly(0, 1);
        assertThat(r.rejected().get(0).reason()).contains("찾지 못했다");
        assertThat(r.rejected().get(1).reason()).contains("여러 곳");
        assertThat(r.usable()).isTrue();
        assertThat(r.text()).endsWith("C 는 따로 쓰지 않는다.");
    }

    @Test
    @DisplayName("지우기 — 처음·끝 인용 사이를 지운다, 끝이 처음보다 앞이면 버린다")
    void deleteBetweenTwoQuotes() {
        AnchoredEdit.Result r = AnchoredEdit.apply(DOC, List.of(Operation.delete("A 기능은 요청을", "a.timeout 이다.")));
        assertThat(r.usable()).isTrue();
        assertThat(r.text()).doesNotContain("30초").doesNotContain("a.timeout");

        AnchoredEdit.Result backwards = AnchoredEdit.apply(DOC, List.of(Operation.delete("a.timeout 이다.", "A 기능은 요청을")));
        assertThat(backwards.usable()).isFalse();
        assertThat(backwards.rejected().get(0).reason()).contains("앞에");
    }

    @Test
    @DisplayName("섹션 지우기 — 제목 줄부터 같거나 높은 수준의 다음 제목 전까지(코드 펜스 안의 # 는 제목이 아니다)")
    void deleteSectionStopsAtTheNextHeadingOfSameLevel() {
        AnchoredEdit.Result r = AnchoredEdit.apply(DOC, List.of(Operation.deleteSection("## B 기능")));

        assertThat(r.usable()).isTrue();
        assertThat(r.text()).doesNotContain("B 는 A 를 감싼다").doesNotContain("a.call()").contains("# C 기능").contains("A 기능은 요청을");
    }

    @Test
    @DisplayName("섹션 지우기의 인용이 제목 줄이 아니면 버린다")
    void deleteSectionNeedsAHeading() {
        AnchoredEdit.Result r = AnchoredEdit.apply(DOC, List.of(Operation.deleteSection("B 는 A 를 감싼다.")));
        assertThat(r.usable()).isFalse();
        assertThat(r.rejected().get(0).reason()).contains("제목 줄");
    }

    @Test
    @DisplayName("넣기 — 인용 끝이 속한 문단의 끝 뒤에 빈 줄로 띄워 넣는다")
    void insertGoesAfterTheParagraph() {
        AnchoredEdit.Result r = AnchoredEdit.apply(DOC, List.of(Operation.insertAfter("30초 동안 기다린다.", "> **주의** — 호출 전에 초기화한다.")));

        assertThat(r.usable()).isTrue();
        assertThat(r.text()).contains("설정 키는 a.timeout 이다.\n\n> **주의** — 호출 전에 초기화한다.\n\n[이미지: images/x/1.png]");
    }

    @Test
    @DisplayName("넣기 — 코드 펜스 안을 가리키면 펜스를 닫는 줄 뒤로 민다")
    void insertInsideAFenceMovesPastIt() {
        AnchoredEdit.Result r = AnchoredEdit.apply(DOC, List.of(Operation.insertAfter("a.call();", "> **주의** — 결과를 확인한다.")));

        assertThat(r.usable()).isTrue();
        assertThat(r.text()).contains("a.call();\n```\n\n> **주의** — 결과를 확인한다.");
    }

    @Test
    @DisplayName("같은 글이 이미 있으면 넣지 않는다(G7 — 같은 지시를 두 번 돌려도 한 번)")
    void insertIsIdempotent() {
        String once = AnchoredEdit.apply(DOC, List.of(Operation.insertAfter("C 는 따로 쓴다.", "> **주의** — 끝."))).text();

        AnchoredEdit.Result twice = AnchoredEdit.apply(once, List.of(Operation.insertAfter("C 는 따로 쓴다.", "> **주의** — 끝.")));

        assertThat(twice.usable()).isFalse();
        assertThat(twice.rejected().get(0).reason()).contains("이미 있다");
    }

    @Test
    @DisplayName("그림 표지·코드 펜스를 일부만 건드리는 작업은 버린다(G2)")
    void partialMarkerEditsAreRejected() {
        AnchoredEdit.Result marker = AnchoredEdit.apply(DOC, List.of(Operation.replace("images/x/1.png", "images/y/2.png")));
        assertThat(marker.usable()).isFalse();
        assertThat(marker.rejected().get(0).reason()).contains("일부만");

        AnchoredEdit.Result fence = AnchoredEdit.apply(DOC, List.of(Operation.delete("```java", "a.call();")));
        assertThat(fence.usable()).isFalse();
    }

    @Test
    @DisplayName("지우기가 그림 표지를 통째로 지우는 것은 허용 — 결과 표지 목록이 지운 만큼만 줄어야 한다(G6)")
    void wholeMarkerDeletionIsAllowed() {
        AnchoredEdit.Result r = AnchoredEdit.apply(DOC, List.of(Operation.delete("[이미지: images/x/1.png]", "[이미지 설명: A 화면]")));

        assertThat(r.usable()).isTrue();
        assertThat(r.text()).doesNotContain("images/x/1.png").doesNotContain("A 화면");
    }

    @Test
    @DisplayName("작업끼리 구간이 겹치면 둘 다 버린다 · 작업이 너무 많으면 전체가 무효")
    void overlappingOrTooManyOps() {
        AnchoredEdit.Result clash = AnchoredEdit.apply(DOC, List.of(
                Operation.replace("30초 동안 기다린다", "x"), Operation.replace("기다린다.\n설정", "y")));
        assertThat(clash.usable()).isFalse();
        assertThat(clash.rejected()).hasSize(2).allSatisfy(x -> assertThat(x.reason()).contains("겹친다"));

        List<Operation> six = List.of(Operation.replace("a", "b"), Operation.replace("a", "b"), Operation.replace("a", "b"),
                Operation.replace("a", "b"), Operation.replace("a", "b"), Operation.replace("a", "b"));
        assertThat(AnchoredEdit.apply(DOC, six).problem()).contains("너무 많다");
    }

    @Test
    @DisplayName("크기 상한 — 넣을 글 500자, 바꾸기 새 구간 2,000자, 빈 새 구간은 지우기를 쓰라고 한다(G5)")
    void sizeLimits() {
        assertThat(AnchoredEdit.apply(DOC, List.of(Operation.insertAfter("C 는 따로 쓴다.", "가".repeat(501)))).rejected().get(0).reason())
                .contains("너무 길다");
        assertThat(AnchoredEdit.apply(DOC, List.of(Operation.replace("C 는 따로 쓴다.", "가".repeat(2_001)))).rejected().get(0).reason())
                .contains("너무 길다");
        assertThat(AnchoredEdit.apply(DOC, List.of(Operation.replace("C 는 따로 쓴다.", " "))).rejected().get(0).reason())
                .contains("지우기");
    }
}
