package com.example.ragagent.export;

import com.example.ragagent.ingestion.ChunkSplitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dedup contract for {@link ChunkReassembler} — each artifact {@code ChunkSplitter} adds must be
 * removed on the way back out, and text the author actually wrote must survive untouched.
 */
class ChunkReassemblerTest {

    private static String join(String... chunks) {
        return ChunkReassembler.reassemble(List.of(chunks), 100);
    }

    @Nested
    @DisplayName("재주입된 소제목 (## 소제목 (2))")
    class ReinjectedHeading {

        @Test
        @DisplayName("앞서 나온 헤딩의 (N) 재주입본은 제거된다")
        void removesReinjectedHeading() {
            String out = join(
                    "## 설치 방법\n\n첫 번째 조각 본문입니다.",
                    "## 설치 방법 (1)\n\n두 번째 조각 본문입니다.");

            assertThat(out).contains("## 설치 방법");
            assertThat(out).doesNotContain("(1)");
            assertThat(out).contains("첫 번째 조각 본문입니다.");
            assertThat(out).contains("두 번째 조각 본문입니다.");
            // 헤딩은 정확히 한 번만 남아야 한다
            assertThat(out.split("## 설치 방법", -1)).hasSize(2);
        }

        @Test
        @DisplayName("앞서 나온 적 없는 헤딩이면 저자가 쓴 (N)으로 보고 유지한다")
        void keepsUnseenHeadingWithParenSuffix() {
            String out = join(
                    "## 개요\n\n본문.",
                    "## 부록 (2)\n\n부록 본문.");

            assertThat(out).contains("## 부록 (2)");
        }
    }

    @Nested
    @DisplayName("부모 챕터 breadcrumb")
    class ParentBreadcrumb {

        @Test
        @DisplayName("이미 나온 부모 헤딩이 자식 헤딩 앞에 복제됐으면 제거된다")
        void removesDuplicatedParentHeading() {
            String out = join(
                    "## 설정\n\n설정 챕터 도입부.",
                    "## 설정\n### 데이터베이스\n\n디비 설정 본문.");

            assertThat(out).contains("### 데이터베이스");
            assertThat(out).contains("디비 설정 본문.");
            assertThat(out.split("## 설정", -1)).hasSize(2); // 부모 헤딩 1회만
        }

        @Test
        @DisplayName("부모 헤딩이 아직 안 나왔으면 유지한다")
        void keepsUnseenParentHeading() {
            String out = join(
                    "# 문서 제목\n\n도입부.",
                    "## 설정\n### 데이터베이스\n\n본문.");

            assertThat(out).contains("## 설정");
            assertThat(out).contains("### 데이터베이스");
        }

        @Test
        @DisplayName("같은 레벨 헤딩이 연속하면 breadcrumb가 아니므로 유지한다")
        void keepsSiblingHeadings() {
            String out = join(
                    "## 1장\n\n본문.",
                    "## 1장\n## 2장\n\n본문2.");

            // 두 번째 청크의 선두 '## 1장'은 자식 헤딩이 뒤따르지 않으므로 breadcrumb가 아니다
            assertThat(out).contains("## 2장");
        }
    }

    @Nested
    @DisplayName("코드 펜스 이어짐 마커")
    class CodeContinuation {

        @Test
        @DisplayName("잘린 코드 블록이 원래 펜스 하나로 다시 합쳐진다")
        void rejoinsSplitCodeFence() {
            String out = join(
                    "```java\npublic void a() {\n```\n" + ChunkSplitter.CODE_CONTINUATION_AFTER,
                    ChunkSplitter.CODE_CONTINUATION_BEFORE + "\n```java\n    return 1;\n}\n```");

            assertThat(out).doesNotContain(ChunkSplitter.CODE_CONTINUATION_BEFORE);
            assertThat(out).doesNotContain(ChunkSplitter.CODE_CONTINUATION_AFTER);
            assertThat(out).contains("public void a() {");
            assertThat(out).contains("return 1;");
            // 펜스는 여는 것 1개 + 닫는 것 1개만 남아야 한다
            assertThat(out.split("```", -1)).hasSize(3);
        }
    }

    @Nested
    @DisplayName("표 헤더 재주입")
    class TableHeader {

        @Test
        @DisplayName("앞 청크에 이미 있는 표 헤더가 복제됐으면 제거된다")
        void removesDuplicatedTableHeader() {
            String out = join(
                    "| 이름 | 설명 |\n| --- | --- |\n| a | 첫째 |",
                    "| 이름 | 설명 |\n| --- | --- |\n| b | 둘째 |");

            assertThat(out).contains("| b | 둘째 |");
            assertThat(out.split("\\| 이름 \\| 설명 \\|", -1)).hasSize(2); // 헤더 1회만
        }
    }

    @Nested
    @DisplayName("슬라이딩 윈도우 overlap")
    class Overlap {

        @Test
        @DisplayName("겹치는 본문은 한 번만 남는다")
        void removesOverlappingText() {
            String shared = "이 문장은 두 청크에 걸쳐 중복으로 들어가 있는 충분히 긴 문장입니다.";
            String out = join(
                    "앞부분 내용입니다.\n" + shared,
                    shared + "\n뒷부분 내용입니다.");

            assertThat(out).contains("앞부분 내용입니다.");
            assertThat(out).contains("뒷부분 내용입니다.");
            assertThat(out.split(java.util.regex.Pattern.quote(shared), -1)).hasSize(2); // 1회만
        }

        @Test
        @DisplayName("짧은 우연한 일치는 overlap으로 오인하지 않는다")
        void ignoresShortCoincidentalMatch() {
            String out = join("첫 청크 끝.", "끝. 둘째 청크 시작.");

            assertThat(out).contains("첫 청크 끝.");
            assertThat(out).contains("끝. 둘째 청크 시작.");
        }

        @Test
        @DisplayName("사용자가 /admin에서 청크를 수정해 겹침이 깨져도 내용이 사라지지 않는다")
        void editedChunkDegradesToDuplicateNeverToDataLoss() {
            // 인덱싱 당시엔 겹쳤지만, 사용자가 두 번째 청크 앞부분을 고쳐 더 이상 일치하지 않는 상태.
            String tail = "이 문장은 원래 두 청크에 걸쳐 중복되어 있던 충분히 긴 문장입니다.";
            String out = join(
                    "앞부분 내용입니다.\n" + tail,
                    "사용자가 고쳐 쓴 완전히 다른 도입부입니다.\n뒷부분 내용입니다.");

            // 겹침을 못 찾으면 지우지 않는다 — 중복이 남을지언정 원문이 유실되지는 않는다
            assertThat(out).contains("앞부분 내용입니다.");
            assertThat(out).contains(tail);
            assertThat(out).contains("사용자가 고쳐 쓴 완전히 다른 도입부입니다.");
            assertThat(out).contains("뒷부분 내용입니다.");
        }

        @Test
        @DisplayName("제거되는 겹침은 항상 앞 내용과 글자 단위로 동일 — 고유 내용은 못 지운다")
        void onlyEverRemovesProvablyDuplicateText() {
            String shared = "완전히 동일하게 중복된 충분히 긴 구간입니다 그렇습니다.";
            String uniqueTail = "이 문장은 두 번째 청크에만 있는 고유한 내용입니다.";
            String out = join("앞 내용.\n" + shared, shared + "\n" + uniqueTail);

            assertThat(out).contains(uniqueTail);                 // 고유 내용 보존
            assertThat(out.split(java.util.regex.Pattern.quote(shared), -1)).hasSize(2); // 중복만 1회로
        }

        @Test
        @DisplayName("겹침이 없으면 빈 줄로 구분해 이어붙인다")
        void separatesNonOverlappingChunks() {
            String out = join("## A\n\n본문 에이.", "## B\n\n본문 비.");

            assertThat(out).isEqualTo("## A\n\n본문 에이.\n\n## B\n\n본문 비.");
        }
    }

    @Nested
    @DisplayName("입력 방어")
    class Defensive {

        @Test
        @DisplayName("빈 목록/널/공백 청크를 안전하게 처리한다")
        void handlesEmptyInput() {
            assertThat(ChunkReassembler.reassemble(List.of(), 100)).isEmpty();
            assertThat(ChunkReassembler.reassemble(null, 100)).isEmpty();
            assertThat(ChunkReassembler.reassemble(java.util.Arrays.asList(null, "  ", "본문"), 100))
                    .isEqualTo("본문");
        }

        @Test
        @DisplayName("overlap=0이면 overlap 제거를 시도하지 않는다")
        void zeroOverlapDisablesOverlapStripping() {
            String shared = "충분히 긴 공통 문장이 여기에 들어 있습니다 그렇습니다.";
            String out = ChunkReassembler.reassemble(List.of("A\n" + shared, shared + "\nB"), 0);

            assertThat(out.split(java.util.regex.Pattern.quote(shared), -1)).hasSize(3); // 그대로 2회
        }
    }
}
