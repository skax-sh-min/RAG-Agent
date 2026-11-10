package com.example.ragagent.tool;

import com.example.ragagent.tool.catalog.CatalogQueryTool;
import com.example.ragagent.tool.change.DiffTool;
import com.example.ragagent.tool.knowledge.DocumentLookupTool;
import com.example.ragagent.tool.knowledge.KnowledgeSearchTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool 계층의 경계를 <b>빌드가 지키게</b> 한다 (AGENT_MCP_DESIGN §2.1 · §2.2 · §7 1b 완료 기준).
 * 이 계층이 큐레이션 코퍼스의 진입 메서드, 쓰기 메서드, 민감 테이블에 닿는 코드를 갖게 되면 여기서 실패한다.
 *
 * <p>검사는 두 가지다. 소스 — {@code tool} 패키지와 {@code CatalogQueryService} 에 금지된 이름이 없다. 리플렉션 — 모든 {@code @Tool} 메서드의
 * 인자는 {@code @ToolParam}(모델이 채우는 값)이거나 {@code ToolContext}(서버가 채우는 값)이고, 이름이 {@code version}/{@code userId} 인 인자는 없다.
 */
class ToolBoundaryConventionTest {

    /** 이 계층이 쓰면 안 되는 클래스·메서드 이름. 큐레이션 진입, 쓰기, 전 사용자 데이터, 직접 SQL. */
    private static final List<String> FORBIDDEN_CODE = List.of(
            "CuratedQaService", "CuratedSubmissionService", "CuratedSubmissionRepository", "CuratedImageStore", "CuratedQaRepository",
            "RetrievalMetricsService", "QuestionReuseService", "QuestionReuseRepository", "ThreadAdminService", "ThreadAdminRepository",
            "SettingsOverrideRepository", "ProviderCatalog", "SqliteUserDetailsService", "MemoryRepository",
            "JdbcTemplate", "DataSource",
            "indexDocument", "syncDirectory", "deleteDocument", "updateChunk", "reindexChunk", "reindexFromMd", "deleteChunk",
            "updateDocumentTags", "updateDisplayName", "createFromSubmission", "createFromLikedTurn");

    /** 민감 테이블·예약 네임스페이스 — 이 계층 소스에 문자열로도 나오면 안 된다. */
    private static final List<String> FORBIDDEN_NAMES = List.of(
            "app_secret", "persistent_logins", "settings_override", "llm_provider_override", "curated_submission", "curated_qa_row");

    private static final List<Class<?>> TOOLS = List.of(
            KnowledgeSearchTool.class, DocumentLookupTool.class, CatalogQueryTool.class, DiffTool.class, com.example.ragagent.tool.change.ChangeRequestTool.class,
            com.example.ragagent.tool.change.ImpactAnalysisTool.class);

    private static List<Path> sources() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main/java/com/example/ragagent/tool"))) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
        }
        out.add(Path.of("src/main/java/com/example/ragagent/service/CatalogQueryService.java"));
        return out;
    }

    /** 주석을 걷은 코드 — 설명 문장이 금지어를 말하는 것까지 막으면 안 된다. */
    private static String codeOnly(Path p) throws IOException {
        return Files.readString(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("Tool 계층 소스에 큐레이션 진입 · 쓰기 · 전 사용자 데이터 · 직접 SQL 이 없다")
    void noForbiddenCode() throws IOException {
        List<Path> sources = sources();
        assertThat(sources).as("검사 대상을 찾았다").hasSizeGreaterThan(8);
        for (Path p : sources) {
            String code = codeOnly(p);
            for (String word : FORBIDDEN_CODE) {
                assertThat(code).as("%s 에 %s", p.getFileName(), word).doesNotContain(word);
            }
        }
    }

    @Test
    @DisplayName("Tool 계층 소스에 민감 테이블 이름이 문자열로도 나오지 않는다")
    void noSensitiveTableNames() throws IOException {
        for (Path p : sources()) {
            String code = codeOnly(p);
            for (String name : FORBIDDEN_NAMES) {
                assertThat(code).as("%s 에 %s", p.getFileName(), name).doesNotContain(name);
            }
        }
    }

    @Test
    @DisplayName("모든 @Tool 메서드의 인자는 @ToolParam 이거나 ToolContext 이고, version · userId 인자는 없다")
    void toolParametersAreModelFacingOrContext() {
        int toolMethods = 0;
        for (Class<?> c : TOOLS) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(Tool.class)) continue;
                toolMethods++;
                for (Parameter p : m.getParameters()) {
                    boolean context = p.getType() == ToolContext.class;
                    assertThat(context || p.isAnnotationPresent(ToolParam.class))
                            .as("%s.%s(%s)", c.getSimpleName(), m.getName(), p.getName()).isTrue();
                    assertThat(p.getName().toLowerCase()).as("%s.%s", c.getSimpleName(), m.getName())
                            .isNotIn("version", "userid", "user", "owner", "collection", "namespace", "caller", "admin");
                }
                // ToolContext 는 마지막 인자다 — 호출자를 받는 자리가 메서드마다 같다
                Parameter[] ps = m.getParameters();
                assertThat(ps[ps.length - 1].getType()).as("%s.%s 의 마지막 인자", c.getSimpleName(), m.getName()).isEqualTo(ToolContext.class);
            }
        }
        assertThat(toolMethods).isEqualTo(8);
    }

    @Test
    @DisplayName("@Tool 메서드를 가진 클래스는 모두 이 검사 목록에 있다 — 새 Tool 클래스가 검사를 빠져나가지 못한다")
    void everyToolClassIsCovered() throws IOException {
        try (Stream<Path> s = Files.walk(Path.of("src/main/java/com/example/ragagent/tool"))) {
            List<String> withTool = s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try { return Files.readString(p).contains("@Tool("); } catch (IOException e) { return false; }
                    })
                    .map(p -> p.getFileName().toString().replace(".java", "")).toList();
            assertThat(withTool).containsExactlyInAnyOrderElementsOf(TOOLS.stream().map(Class::getSimpleName).toList());
        }
    }
}
