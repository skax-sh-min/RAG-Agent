package com.example.ragagent.repository;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.VerificationSnapshot;
import com.example.ragagent.service.MemoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 검증 결과가 저장되고 되살아나는가 (PLAN §6.24 Step 4-b).
 *
 * <p>배지가 새로고침 후에도 남아야 한다는 요구가 이 저장의 이유다. 화면에서만 배지를 그리면
 * 사용자는 "문서 밖 이름" 경고를 본 뒤 새로고침 한 번으로 그것을 잃는데, C 모드에서 그 값은
 * 안전 신호다 — §6.24 Step 1-d 가 답변 본문에 대해 닫은 것과 같은 종류의 불일치다.
 *
 * <p>실제 SQLite 에 쓰고 읽는다. 진짜 컬럼(방어적 {@code ALTER TABLE})과 JSON 왕복이 이 기능의
 * 전부라, 목킹하면 검증되는 것이 없다.
 */
class VerificationPersistenceTest {

    private Path dbFile;
    private SqliteMemoryRepository repo;
    private MemoryService service;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-test-verification-", ".db");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + dbFile));
        AppProperties props = mock(AppProperties.class);
        when(props.memorySafe()).thenReturn(new AppProperties.MemoryConfig(50));
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(), 2, 10, 180, "COST_FIRST", 0.6, 3, 20, 0.0, 0.1, 0.0, 0.7, 6000, false));
        repo = new SqliteMemoryRepository(jdbc, props);
        repo.init();
        service = new MemoryService(repo, props);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    private long newTurn() {
        return repo.addTurn("u1", "t1", "질문", "답변", "2026-08-24", 0, 0, 0, "local", 1, "N", null);
    }

    @Test
    @DisplayName("저장한 검증 결과가 그대로 되살아난다 (발명된 이름 목록까지)")
    void roundTrip() {
        long turnId = newTurn();
        service.saveVerification(turnId, new VerificationSnapshot(
                true, true, null, "포트는 환경마다 다릅니다", List.of("parseDateEx", "--strict-mode")));

        VerificationSnapshot back = service.getVerifications(List.of(turnId)).get(turnId);

        assertThat(back).isNotNull();
        assertThat(back.grounded()).isTrue();
        assertThat(back.generative()).isTrue();
        assertThat(back.envNote()).isEqualTo("포트는 환경마다 다릅니다");
        assertThat(back.inventedSymbols()).containsExactly("parseDateEx", "--strict-mode");
        // 배지 규칙은 되살린 뒤에도 같은 답을 낸다 — 새로고침 전후가 같다는 것이 이 값의 존재 이유다.
        assertThat(back.verdictLabel()).isEqualTo("생성");
        assertThat(back.hasInventedSymbols()).isTrue();
    }

    @Test
    @DisplayName("담을 것이 없는 턴은 저장하지 않아 조회 결과에서 아예 빠진다 (= 배지 없음)")
    void emptySnapshotIsNotStored() {
        long turnId = newTurn();
        service.saveVerification(turnId, new VerificationSnapshot(null, false, null, null, List.of()));

        // 이 컬럼이 생기기 전의 모든 턴과 meta/Direct·S 턴이 이 상태다 — 예전 동작 그대로.
        assertThat(service.getVerifications(List.of(turnId))).isEmpty();
    }

    @Test
    @DisplayName("검증 기록이 없는 턴은 조회에서 조용히 빠진다 (구 데이터 하위호환)")
    void turnsWithoutVerificationAreAbsent() {
        long a = newTurn();
        long b = newTurn();
        service.saveVerification(a, new VerificationSnapshot(false, false, "근거 부족", null, List.of()));

        Map<Long, VerificationSnapshot> found = service.getVerifications(List.of(a, b));

        assertThat(found).containsOnlyKeys(a);
        assertThat(found.get(a).verdictLabel()).isEqualTo("미검증");
    }

    @Test
    @DisplayName("알 수 없는 필드가 있어도 읽힌다 — 이 행들은 자신을 쓴 코드보다 오래 산다")
    void unknownFieldsAreTolerated() {
        long turnId = newTurn();
        // 필드가 하나 추가된 뒤 옛 코드가 읽는 상황(또는 그 반대)을 흉내낸다. 엄격 파싱이면
        // 이 한 줄 때문에 과거 기록 전체의 배지가 사라진다.
        repo.saveVerification(turnId,
                "{\"grounded\":true,\"generative\":true,\"inventedSymbols\":[\"x\"],\"futureField\":42}");

        VerificationSnapshot back = service.getVerifications(List.of(turnId)).get(turnId);

        assertThat(back).isNotNull();
        assertThat(back.verdictLabel()).isEqualTo("생성");
        assertThat(back.inventedSymbols()).containsExactly("x");
    }

    @Test
    @DisplayName("깨진 JSON은 그 턴만 건너뛴다 — 화면 전체를 죽이지 않는다")
    void brokenJsonSkipsOnlyThatTurn() {
        long good = newTurn();
        long broken = newTurn();
        service.saveVerification(good, new VerificationSnapshot(true, false, null, null, List.of()));
        repo.saveVerification(broken, "{not json");

        Map<Long, VerificationSnapshot> found = service.getVerifications(List.of(good, broken));

        assertThat(found).containsOnlyKeys(good);
    }

    @Test
    @DisplayName("빈 turn id 목록은 쿼리를 돌리지 않는다 (IN () 는 유효한 SQL이 아니다)")
    void emptyIdListShortCircuits() {
        assertThat(service.getVerifications(List.of())).isEmpty();
        assertThat(repo.findVerificationsByTurnIds(null)).isEmpty();
    }
}
