package com.example.ragagent.workflow;

import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 검토 화면 강조(R5)와 일괄 적용 규칙(R10) — 순수 클래스 둘. */
class ReviewDiffTest {

    private static String joined(List<ReviewDiff.Row> rows) {
        return rows.stream().map(r -> r.segs().stream().map(ReviewDiff.Seg::text).collect(Collectors.joining()))
                .collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("수정 후 강조 층을 이은 글은 입력과 정확히 같다 — 더한 줄은 add, 수정 전 칸에는 사라진 줄만 del")
    void backdropTextEqualsInput() {
        String before = "# 7.10\n\n| a | b |\ncallBatchNow 즉시 호출\n";
        String after = "# 7.10\n\n> **사용 중단** — callBatchNow\n| a | b |\ncallBatchNow 바로 호출\n";

        ReviewDiff.Panes p = ReviewDiff.of(before, after);

        assertThat(p.aligned()).isTrue();
        assertThat(joined(p.after())).isEqualTo(after);
        assertThat(joined(p.before())).isEqualTo(before);
        assertThat(p.after()).anyMatch(r -> r.mark().equals("add") && joined(List.of(r)).startsWith("> **사용 중단**"));
        assertThat(p.before()).noneMatch(r -> r.mark().equals("add"));
        assertThat(p.after()).noneMatch(r -> r.mark().equals("del"));
        // 바뀐 줄 안의 낱말 강조
        assertThat(p.after()).anyMatch(r -> r.segs().stream().anyMatch(s -> s.changed() && s.text().contains("바로")));
    }

    @Test
    @DisplayName("CRLF 입력도 줄을 맞추고, 끝 줄바꿈의 빈 줄도 한 줄로 남는다")
    void crlfAndTrailingNewline() {
        ReviewDiff.Panes p = ReviewDiff.of("가\r\n나\r\n", "가\r\n다\r\n");
        assertThat(p.aligned()).isTrue();
        assertThat(joined(p.after())).isEqualTo("가\n다\n");
        assertThat(p.after().get(p.after().size() - 1).segs().get(0).text()).isEmpty();
    }

    @Test
    @DisplayName("같은 글이면 강조가 없다")
    void identical() {
        ReviewDiff.Panes p = ReviewDiff.of("가\n나", "가\n나");
        assertThat(p.identical()).isTrue();
        assertThat(p.after()).noneMatch(ReviewDiff.Row::changed);
    }

    private static ChangeProposalRepository.Proposal edit(String before, String after, String kind) {
        return new ChangeProposalRepository.Proposal(1, 1, "d", "d.pdf", "c", kind, before, after, "h",
                null, null, null, null, "r", "pending", null, null, null, null, "t");
    }

    private static ChangeJobRepository.Job job(String intent) {
        return new ChangeJobRepository.Job(1, ChangeJobRepository.KIND_MAINTENANCE, "x", "root", "done", "{}", null, "t", "t", intent);
    }

    @Test
    @DisplayName("'넣기만' 판정 — 줄만 더한 편집만 참, 원문 줄을 바꾸거나 지웠거나 편집이 아니면 거짓")
    void insertOnly() {
        assertThat(ReviewRules.insertOnly(edit("가\n나", "> 안내\n가\n나", "edit"))).isTrue();
        assertThat(ReviewRules.insertOnly(edit("가\n나", "> 안내\n가\n나 바꿈", "edit"))).isFalse();
        assertThat(ReviewRules.insertOnly(edit("가\n나", "가", "edit"))).isFalse();
        assertThat(ReviewRules.insertOnly(edit("가\n나", "> 안내\n가\n나", "literal"))).isFalse();
    }

    @Test
    @DisplayName("일괄 적용은 자연어 deprecate·add_note 작업에만")
    void batchAllowed() {
        assertThat(ReviewRules.batchAllowed(job("{\"mode\":\"nl\",\"type\":\"deprecate\"}"))).isTrue();
        assertThat(ReviewRules.batchAllowed(job("{\"mode\":\"nl\",\"type\":\"add_note\"}"))).isTrue();
        assertThat(ReviewRules.batchAllowed(job("{\"mode\":\"nl\",\"type\":\"remove\"}"))).isFalse();
        assertThat(ReviewRules.batchAllowed(job("{\"mode\":\"nl\",\"type\":\"revise\"}"))).isFalse();
        assertThat(ReviewRules.batchAllowed(job("{\"mode\":\"external\"}"))).isFalse();
        assertThat(ReviewRules.batchAllowed(job(null))).isFalse();
        assertThat(ReviewRules.batchAllowed(job("not json"))).isFalse();
    }
}
