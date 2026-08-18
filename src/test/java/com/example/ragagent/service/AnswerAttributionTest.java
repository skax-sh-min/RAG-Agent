package com.example.ragagent.service;

import com.example.ragagent.model.SourceRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * QA — 응답 참여도 귀속 (2단계)
 *
 * <p>The contract under test is deliberately modest: shares are an <em>estimate</em> of how much
 * of the answer's text resembles each chunk. These tests pin the properties that make the number
 * safe to show — it never invents attribution, it sums to 1, and it degrades to "no answer" rather
 * than to a wrong answer.
 */
class AnswerAttributionTest {

    private static Document doc(String id, String text) {
        return Document.builder().id(id).text(text).metadata(Map.of("filename", id)).build();
    }

    /** Two chunks with no vocabulary in common, so assignment is unambiguous. */
    private static final Document PORT_DOC = doc("c-port",
            "서버 기본 포트는 8080이며 SERVER_PORT 환경변수로 변경할 수 있습니다. "
            + "포트 충돌이 발생하면 다른 값을 지정하세요.");
    private static final Document BACKUP_DOC = doc("c-backup",
            "백업은 매일 새벽 3시에 수행됩니다. 보관 주기는 30일이며 오래된 스냅샷은 자동 삭제됩니다.");

    @Nested
    @DisplayName("기본 귀속")
    class BasicAttribution {

        @Test
        @DisplayName("한 청크에서만 온 답변은 그 청크가 100%를 가져간다")
        void singleSourceAnswerGetsFullShare() {
            String answer = "서버 기본 포트는 8080이며 SERVER_PORT 환경변수로 변경할 수 있습니다.";

            var r = AnswerAttribution.compute(answer, List.of(PORT_DOC, BACKUP_DOC), List.of());

            assertThat(r.method()).isEqualTo(AnswerAttribution.Method.LEXICAL);
            assertThat(r.sharesByChunkId()).containsOnlyKeys("c-port");
            assertThat(r.sharesByChunkId().get("c-port")).isCloseTo(1.0, within(1e-9));
        }

        @Test
        @DisplayName("두 청크에서 온 답변은 문장 글자수 비율로 나뉘고 합이 1.0이다")
        void twoSourcesSplitByCharsAndSumToOne() {
            String answer = """
                    서버 기본 포트는 8080이며 SERVER_PORT 환경변수로 변경할 수 있습니다.
                    백업은 매일 새벽 3시에 수행됩니다.
                    """;

            var r = AnswerAttribution.compute(answer, List.of(PORT_DOC, BACKUP_DOC), List.of());

            assertThat(r.sharesByChunkId()).containsOnlyKeys("c-port", "c-backup");
            assertThat(r.sharesByChunkId().values().stream().mapToDouble(Double::doubleValue).sum())
                    .isCloseTo(1.0, within(1e-9));
            // 첫 문장이 더 길므로 포트 청크의 몫이 더 크다 (글자수 가중의 직접 증거)
            assertThat(r.sharesByChunkId().get("c-port"))
                    .isGreaterThan(r.sharesByChunkId().get("c-backup"));
        }

        @Test
        @DisplayName("검색된 어느 청크와도 겹치지 않는 답변은 NONE — 억지로 배정하지 않는다")
        void unrelatedAnswerYieldsNone() {
            String answer = "죄송하지만 해당 내용은 제공된 문서에서 확인되지 않습니다.";

            var r = AnswerAttribution.compute(answer, List.of(PORT_DOC, BACKUP_DOC), List.of());

            assertThat(r.method()).isEqualTo(AnswerAttribution.Method.NONE);
            assertThat(r.sharesByChunkId()).isEmpty();
        }
    }

    @Nested
    @DisplayName("인용 신호(usedDocs)")
    class CitationSignal {

        @Test
        @DisplayName("인용된 문서로 후보를 좁힌다 — 다른 청크는 아예 배정 대상이 아니다")
        void citationRestrictsCandidates() {
            String answer = "백업은 매일 새벽 3시에 수행됩니다. 보관 주기는 30일입니다.";

            // 2번(BACKUP_DOC)만 인용됐다고 보고 → 1번은 후보에서 빠진다
            var r = AnswerAttribution.compute(answer, List.of(PORT_DOC, BACKUP_DOC), List.of(2));

            assertThat(r.method()).isEqualTo(AnswerAttribution.Method.CITATION_LEXICAL);
            assertThat(r.sharesByChunkId()).containsOnlyKeys("c-backup");
        }

        @Test
        @DisplayName("인용은 후보를 좁힐 뿐, 겹치지 않는 문서에 몫을 만들어주지는 않는다")
        void citationNeverManufacturesShare() {
            // 모델이 1번을 인용했다고 주장하지만 답변 내용은 1번과 무관하다.
            String answer = "죄송하지만 해당 내용은 제공된 문서에서 확인되지 않습니다.";

            var r = AnswerAttribution.compute(answer, List.of(PORT_DOC, BACKUP_DOC), List.of(1));

            assertThat(r.method()).isEqualTo(AnswerAttribution.Method.NONE);
        }

        @Test
        @DisplayName("범위를 벗어난 인용 번호는 무시된다 — 모델 출력이므로 신뢰하지 않는다")
        void outOfRangeCitationsIgnored() {
            String answer = "서버 기본 포트는 8080이며 SERVER_PORT 환경변수로 변경할 수 있습니다.";

            // 99는 존재하지 않는 번호, 1은 유효 → 유효한 것만 남아 후보가 된다
            var r = AnswerAttribution.compute(answer, List.of(PORT_DOC, BACKUP_DOC), List.of(99, 1));

            assertThat(r.method()).isEqualTo(AnswerAttribution.Method.CITATION_LEXICAL);
            assertThat(r.sharesByChunkId()).containsOnlyKeys("c-port");
        }

        @Test
        @DisplayName("유효한 인용 번호가 하나도 없으면 NONE — 전체 문서로 조용히 되돌아가지 않는다")
        void allInvalidCitationsYieldNone() {
            String answer = "서버 기본 포트는 8080이며 SERVER_PORT 환경변수로 변경할 수 있습니다.";

            var r = AnswerAttribution.compute(answer, List.of(PORT_DOC, BACKUP_DOC), List.of(0, 42));

            assertThat(r.method()).isEqualTo(AnswerAttribution.Method.LEXICAL);
            assertThat(r.sharesByChunkId()).containsOnlyKeys("c-port");
        }
    }

    @Nested
    @DisplayName("잡음 제거")
    class NoiseHandling {

        @Test
        @DisplayName("마크다운 헤딩 줄은 문장에서 제외된다 — 매 턴 동일한 답변 골격이라 귀속 정보가 없다")
        void markdownHeadingsExcluded() {
            assertThat(AnswerAttribution.splitSentences("## 요약\n포트는 8080입니다.\n### 상세 설명"))
                    .containsExactly("포트는 8080입니다.");
        }

        @Test
        @DisplayName("공통 보일러플레이트는 희소도 가중으로 눌린다 — 고유 내용이 있는 청크가 이긴다")
        void rareNgramsWinOverBoilerplate() {
            // 두 청크가 같은 머리말을 공유하고, 뒤쪽만 서로 다르다.
            String shared = "본 문서는 시스템 운영 가이드의 일부입니다. 자세한 내용은 관리자에게 문의하십시오. ";
            Document a = doc("c-a", shared + "인증 토큰의 유효기간은 3600초입니다.");
            Document b = doc("c-b", shared + "로그 파일은 하루 단위로 회전됩니다.");

            String answer = "인증 토큰의 유효기간은 3600초입니다.";

            var r = AnswerAttribution.compute(answer, List.of(a, b), List.of());

            assertThat(r.sharesByChunkId()).containsOnlyKeys("c-a");
        }

        @Test
        @DisplayName("빈 답변·빈 문서 목록은 NONE")
        void emptyInputsYieldNone() {
            assertThat(AnswerAttribution.compute("", List.of(PORT_DOC), List.of()).method())
                    .isEqualTo(AnswerAttribution.Method.NONE);
            assertThat(AnswerAttribution.compute("답변", List.of(), List.of()).method())
                    .isEqualTo(AnswerAttribution.Method.NONE);
            assertThat(AnswerAttribution.compute(null, List.of(PORT_DOC), null).method())
                    .isEqualTo(AnswerAttribution.Method.NONE);
        }
    }

    @Nested
    @DisplayName("SourceRef 반영")
    class ApplyToSources {

        @Test
        @DisplayName("chunkId로 매칭해 몫을 싣고, 배정받지 못한 출처는 null로 남는다")
        void appliesByChunkIdAndLeavesUnmatchedNull() {
            List<SourceRef> sources = List.of(
                    new SourceRef("포트 문서", "p", "c-port", "d1", 1, 0.8, 0.5, "vec:1"),
                    new SourceRef("백업 문서", "p", "c-backup", "d2", 2, 0.4, 0.5, "vec:2"));

            var result = new AnswerAttribution.Result(
                    Map.of("c-port", 1.0), AnswerAttribution.Method.LEXICAL);
            List<SourceRef> out = AnswerAttribution.applyTo(sources, result);

            assertThat(out.get(0).answerShare()).isEqualTo(1.0);
            assertThat(out.get(1).answerShare()).isNull();
            // 1단계 검색 수치는 그대로 보존된다
            assertThat(out.get(0).similarity()).isEqualTo(0.8);
            assertThat(out.get(1).axisRanks()).isEqualTo("vec:2");
        }

        @Test
        @DisplayName("NONE이면 출처 목록을 손대지 않는다")
        void noneLeavesSourcesUntouched() {
            List<SourceRef> sources = List.of(
                    new SourceRef("포트 문서", "p", "c-port", "d1", 1, 0.8, 0.5, "vec:1"));

            assertThat(AnswerAttribution.applyTo(sources, AnswerAttribution.Result.none()))
                    .isSameAs(sources);
        }
    }
}
