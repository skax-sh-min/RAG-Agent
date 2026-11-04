package com.example.ragagent.controller;

import com.example.ragagent.model.MetaKey;
import com.example.ragagent.service.AdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /admin} 청크 목록 프래그먼트({@code fragments/admin-chunks :: table})를 실제 템플릿으로 그려 본다 —
 * 스프링 컨텍스트 없이 Thymeleaf 만 띄운다.
 */
class AdminChunksFragmentTest {

    private static String render(List<AdminService.ChunkRow> chunks) {
        return render(chunks, Map.of());
    }

    private static String render(List<AdminService.ChunkRow> chunks, Map<String, String> docLabels) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        Context ctx = new Context();
        ctx.setVariable("collection", "latest");
        ctx.setVariable("docId", "");
        ctx.setVariable("documents", List.of());
        ctx.setVariable("docLabels", docLabels);
        ctx.setVariable("chunks", chunks);
        ctx.setVariable("offset", 0);
        ctx.setVariable("limit", 20);
        return engine.process("fragments/admin-chunks", Set.of("table"), ctx);
    }

    private static AdminService.ChunkRow row(String id, String preview, Map<String, String> meta) {
        return new AdminService.ChunkRow(id, preview, preview, meta);
    }

    @Test
    @DisplayName("수정일시 열은 xl(1200px) 이상에서만 보인다 — 머리글과 칸이 같은 클래스를 단다")
    void editedAtColumn_isHiddenBelowXl() {
        String html = render(List.of(row("c1", "본문", Map.of(MetaKey.EDITED_AT, "2026-11-04T01:02:03Z"))));

        assertThat(html).contains("수정일시");
        // 머리글 1 + 칸 1 — 둘 중 하나만 숨겨지면 좁은 화면에서 열이 어긋난다
        assertThat(html.split("d-none d-xl-table-cell", -1).length - 1).isEqualTo(2);
        assertThat(html).contains("<time datetime=\"2026-11-04T01:02:03Z\"").contains("utc-time");
    }

    @Test
    @DisplayName("편집한 적 없어도 인덱싱 시각(collected_at)이 수정일시로 보인다")
    void neverEditedChunk_showsIndexedTime() {
        String html = render(List.of(row("c1", "본문", Map.of(MetaKey.COLLECTED_AT, "2026-11-01T00:00:00Z"))));

        assertThat(html).contains("<time datetime=\"2026-11-01T00:00:00Z\"");
    }

    @Test
    @DisplayName("편집·인덱싱 시각이 둘 다 없는 청크는 수정일시 칸이 빈다")
    void chunkWithoutAnyStamp_hasEmptyEditedAtCell() {
        String html = render(List.of(row("c1", "본문", Map.of())));

        assertThat(html).doesNotContain("<time");
    }

    @Test
    @DisplayName("본문 풍선(title)은 연속 줄바꿈이 한 번으로 줄어 있다")
    void previewTitle_hasNoBlankLines() {
        String html = render(List.of(row("c1", "첫 문단\n\n\n둘째 문단", Map.of())));

        // title 속성만 본다 — 셀 본문(th:text)은 text-truncate 라 CSS 가 공백을 한 줄로 접는다
        assertThat(html).contains("title=\"첫 문단\n둘째 문단\"");
    }

    @Test
    @DisplayName("수정일시 칸은 줄바꿈을 막지 않는다 — 폭이 모자라면 날짜와 시각이 두 줄이 된다")
    void editedAtCell_mayWrapToTwoLines() {
        String html = render(List.of(row("c1", "본문", Map.of(MetaKey.EDITED_AT, "2026-11-04T01:02:03Z"))));

        int cell = html.indexOf("d-none d-xl-table-cell\" style=\"font-size:0.72rem;");
        assertThat(cell).as("수정일시 칸").isPositive();
        assertThat(html.substring(html.lastIndexOf("<td", cell), cell + 80)).doesNotContain("text-nowrap");
    }

    @Test
    @DisplayName("파일명 칸은 문서 레지스트리의 표시 이름을 보이고, 레지스트리에 없으면 메타데이터 filename 으로 되돌린다")
    void filenameCell_prefersRegistryLabel() {
        String html = render(List.of(
                        row("c1", "본문", Map.of(MetaKey.DOC_ID, "a_1", MetaKey.FILENAME, "a.pdf")),
                        row("c2", "본문", Map.of(MetaKey.DOC_ID, "curated:7", MetaKey.FILENAME, "큐레이션"))),
                Map.of("a_1", "설치 가이드"));

        assertThat(html).contains("title=\"설치 가이드\"").doesNotContain("title=\"a.pdf\"");
        assertThat(html).contains("title=\"큐레이션\"");
    }
}
