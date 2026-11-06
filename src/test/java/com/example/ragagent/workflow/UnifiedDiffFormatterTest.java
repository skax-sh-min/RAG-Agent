package com.example.ragagent.workflow;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.service.ChunkDiff;
import com.example.ragagent.tool.ToolCaller;
import com.example.ragagent.tool.ToolGuard;
import com.example.ragagent.tool.change.DiffTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 축약 unified diff — 바뀐 줄 주위 2줄만, 구간 머리, 출력 상한, 같음·너무 큼, 그리고 generateDiff 의 입력 가드. */
class UnifiedDiffFormatterTest {

    private static String lines(int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(i -> "줄" + i).collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("바뀐 줄 주위 2줄만 남기고 나머지는 접는다 — 구간 머리에 옛/새 시작 줄 번호")
    void keepsOnlyChangedLinesWithContext() {
        String before = lines(1, 20);
        String after = before.replace("줄10", "줄10 수정");

        UnifiedDiffFormatter.Formatted f = UnifiedDiffFormatter.format(ChunkDiff.compare(before, after));

        assertThat(f.addedLines()).isEqualTo(1);
        assertThat(f.removedLines()).isEqualTo(1);
        assertThat(f.text().lines().toList()).containsExactly(
                "@@ -8 +8 @@", " 줄8", " 줄9", "-줄10", "+줄10 수정", " 줄11", " 줄12");
        assertThat(f.truncated()).isFalse();
    }

    @Test
    @DisplayName("멀리 떨어진 두 수정은 구간 둘로 나뉜다")
    void separateHunks() {
        String before = lines(1, 40);
        String after = before.replace("줄5\n", "줄5 수정\n").replace("줄35\n", "줄35 수정\n");

        String text = UnifiedDiffFormatter.format(ChunkDiff.compare(before, after)).text();

        assertThat(text.lines().filter(l -> l.startsWith("@@")).count()).isEqualTo(2);
        assertThat(text).doesNotContain("줄20");
    }

    @Test
    @DisplayName("같은 텍스트는 빈 diff 와 identical, 너무 커서 비교하지 못했으면 tooLarge")
    void identicalAndTooLarge() {
        UnifiedDiffFormatter.Formatted same = UnifiedDiffFormatter.format(ChunkDiff.compare("a\nb", "a\nb"));
        assertThat(same.identical()).isTrue();
        assertThat(same.text()).isEmpty();

        UnifiedDiffFormatter.Formatted big = UnifiedDiffFormatter.format(new ChunkDiff.Result(java.util.List.of(), 0, 0, false, true));
        assertThat(big.tooLarge()).isTrue();
        assertThat(big.text()).isEmpty();

        assertThat(UnifiedDiffFormatter.format(null).text()).isEmpty();
    }

    @Test
    @DisplayName("출력 줄 수에 상한이 있고 넘으면 truncated")
    void outputIsBounded() {
        String before = IntStream.rangeClosed(1, 300).mapToObj(i -> "원본" + i).collect(Collectors.joining("\n"));
        String after = IntStream.rangeClosed(1, 300).mapToObj(i -> "바뀜" + i).collect(Collectors.joining("\n"));

        UnifiedDiffFormatter.Formatted f = UnifiedDiffFormatter.format(ChunkDiff.compare(before, after));

        assertThat(f.truncated()).isTrue();
        assertThat(f.text().lines().count()).isLessThanOrEqualTo(UnifiedDiffFormatter.MAX_OUTPUT_LINES);
    }

    @Test
    @DisplayName("generateDiff — 어느 쪽이든 null 이거나 20,000자를 넘으면 거절한다")
    void toolValidatesInput() {
        DiffTool tool = new DiffTool(new ToolGuard(mock(AuditLogger.class)));
        var ctx = ToolCaller.guest().toContext();

        assertThat(tool.generateDiff("a", "b", ctx).addedLines()).isEqualTo(1);
        assertThatThrownBy(() -> tool.generateDiff(null, "b", ctx)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.generateDiff("a", "가".repeat(20_001), ctx)).isInstanceOf(IllegalArgumentException.class);
    }
}
