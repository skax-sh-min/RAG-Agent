package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.repository.ImageDescriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — LazyVisionService.describeIfNeeded(paths, onProgress): the query-time Vision progress
 * callback backing GraphListener#onImageAnalysisProgress (§ 채팅 이미지 분석 진행 표시).
 */
class LazyVisionServiceTest {

    @TempDir
    Path dataDir;

    private VisionDescriptionService visionService;
    private ImageDescriptionRepository descRepo;
    private ImageTypeClassifier imageTypeClassifier;
    private AppProperties props;
    private LazyVisionService service;

    @BeforeEach
    void setUp() {
        visionService = mock(VisionDescriptionService.class);
        descRepo = mock(ImageDescriptionRepository.class);
        imageTypeClassifier = mock(ImageTypeClassifier.class);
        props = mock(AppProperties.class);

        when(props.dataDir()).thenReturn(dataDir.toString());
        when(props.imageDescriptionSafe())
                .thenReturn(new AppProperties.ImageDescriptionProperties(
                        "strip", false, false, null, 1_000, false, false, false)); // classifyType=false
        when(props.indexingSafe())
                .thenReturn(new AppProperties.IndexingConfig(2, 3, 180, 2));

        service = new LazyVisionService(visionService, descRepo, imageTypeClassifier, props);
    }

    /** Creates a real (tiny, content-irrelevant) file under dataDir at the given relative path. */
    private String createImage(String relPath) throws Exception {
        Path full = dataDir.resolve(relPath);
        Files.createDirectories(full.getParent());
        Files.write(full, new byte[]{1, 2, 3});
        return relPath;
    }

    @Test
    @DisplayName("전부 캐시 히트면 onProgress가 호출되지 않는다")
    void allCached_neverReportsProgress() {
        List<String> paths = List.of("images/a/1.png", "images/a/2.png");
        when(descRepo.findAll(paths)).thenReturn(Map.of(
                "images/a/1.png", "설명1", "images/a/2.png", "설명2"));

        List<int[]> events = new CopyOnWriteArrayList<>();
        Map<String, String> result = service.describeIfNeeded(paths, (done, total) -> events.add(new int[]{done, total}));

        assertThat(result).hasSize(2);
        assertThat(events).isEmpty();
        verify(visionService, never()).describe(any(), anyString());
    }

    @Test
    @DisplayName("이미지 목록이 비어 있으면 onProgress가 호출되지 않는다")
    void emptyList_neverReportsProgress() {
        List<int[]> events = new CopyOnWriteArrayList<>();
        Map<String, String> result = service.describeIfNeeded(List.of(), (done, total) -> events.add(new int[]{done, total}));

        assertThat(result).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("미스 2건 — (0,2)로 시작해 완료마다 증가, 총 개수는 미스 수만 반영(캐시 히트 제외)")
    void misses_reportInitialThenPerCompletion() throws Exception {
        String hit  = createImage("images/a/hit.png");
        String miss1 = createImage("images/a/miss1.png");
        String miss2 = createImage("images/a/miss2.png");
        List<String> paths = List.of(hit, miss1, miss2);

        when(descRepo.findAll(paths)).thenReturn(Map.of(hit, "이미 있는 설명"));
        when(visionService.describe(any(), anyString())).thenReturn("새 설명");

        List<int[]> events = new CopyOnWriteArrayList<>();
        Map<String, String> result = service.describeIfNeeded(paths, (done, total) -> events.add(new int[]{done, total}));

        assertThat(result).hasSize(3);
        assertThat(result.get(hit)).isEqualTo("이미 있는 설명");
        assertThat(result.get(miss1)).isEqualTo("새 설명");
        assertThat(result.get(miss2)).isEqualTo("새 설명");

        // total은 항상 2(미스 수)로 고정 — 캐시 히트는 "분석 대상"에 안 들어감
        assertThat(events).allSatisfy(e -> assertThat(e[1]).isEqualTo(2));
        // (0,2) 최초 통지 + 완료마다 1건씩, 총 1(초기) + 2(완료) = 3건
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).containsExactly(0, 2);
        // 완료 순서는 병렬이라 비결정적 — done 값 집합만 {1,2}인지 확인
        Set<Integer> doneValues = events.stream().skip(1).map(e -> e[0]).collect(java.util.stream.Collectors.toSet());
        assertThat(doneValues).containsExactlyInAnyOrder(1, 2);

        verify(descRepo).save(miss1, "새 설명", null, null);
        verify(descRepo).save(miss2, "새 설명", null, null);
    }

    @Test
    @DisplayName("Vision 호출이 실패해도 진행 카운터는 total에 도달한다 (멈추지 않음)")
    void visionFailure_stillReachesTotal() throws Exception {
        String miss = createImage("images/a/fail.png");
        List<String> paths = List.of(miss);

        when(descRepo.findAll(paths)).thenReturn(Map.of());
        when(visionService.describe(any(), anyString())).thenThrow(new RuntimeException("LLM 오류"));

        List<int[]> events = new CopyOnWriteArrayList<>();
        Map<String, String> result = service.describeIfNeeded(paths, (done, total) -> events.add(new int[]{done, total}));

        assertThat(result).isEmpty();               // 실패한 이미지는 설명 없이 반환됨
        assertThat(events).hasSize(2);               // (0,1) 시작 + (1,1) 완료(실패해도)
        assertThat(events.get(0)).containsExactly(0, 1);
        assertThat(events.get(1)).containsExactly(1, 1);
    }

    @Test
    @DisplayName("존재하지 않는 파일도 진행 카운터가 멈추지 않고 total에 도달한다")
    void missingFile_stillReachesTotal() {
        List<String> paths = List.of("images/a/does-not-exist.png");
        when(descRepo.findAll(paths)).thenReturn(Map.of());

        List<int[]> events = new CopyOnWriteArrayList<>();
        Map<String, String> result = service.describeIfNeeded(paths, (done, total) -> events.add(new int[]{done, total}));

        assertThat(result).isEmpty();
        assertThat(events).hasSize(2);
        assertThat(events.get(events.size() - 1)).containsExactly(1, 1);
        verify(visionService, never()).describe(any(), anyString());
    }

    @Test
    @DisplayName("onProgress를 null로 넘겨도(1-인자 오버로드) 예외 없이 정상 동작한다")
    void nullProgressCallback_isSafe() throws Exception {
        String miss = createImage("images/a/m.png");
        when(descRepo.findAll(List.of(miss))).thenReturn(Map.of());
        when(visionService.describe(any(), anyString())).thenReturn("설명");

        Map<String, String> result = service.describeIfNeeded(List.of(miss)); // 1-인자 오버로드

        assertThat(result.get(miss)).isEqualTo("설명");
    }

    @Test
    @DisplayName("skipRequested가 처음부터 true면 완료를 기다리지 않고 즉시 반환한다")
    void skipRequestedFromStart_returnsImmediately() throws Exception {
        String miss1 = createImage("images/a/1.png");
        String miss2 = createImage("images/a/2.png");
        List<String> paths = List.of(miss1, miss2);
        when(descRepo.findAll(paths)).thenReturn(Map.of());
        // 각 호출이 2초씩 걸리는 것처럼 흉내 — skip이 실제로 대기를 끊는지 확인하는 목적
        when(visionService.describe(any(), anyString())).thenAnswer(inv -> {
            Thread.sleep(2000);
            return "느린 설명";
        });

        long start = System.nanoTime();
        Map<String, String> result = service.describeIfNeeded(paths, null, () -> true);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(result).isEmpty();               // 아직 아무것도 완료되지 않은 시점에 반환
        assertThat(elapsedMs).isLessThan(1000);      // 2초 대기 없이 즉시 반환됨

        // 백그라운드에서는 계속 진행되어 결국 캐시에 저장된다 (이번 턴의 반환값에는 없어도)
        verify(descRepo, timeout(5000)).save(miss1, "느린 설명", null, null);
        verify(descRepo, timeout(5000)).save(miss2, "느린 설명", null, null);
    }

    @Test
    @DisplayName("일부 완료 후 skip이 요청되면 그때까지 완료된 것만 반환하고 나머지는 기다리지 않는다")
    void skipRequestedMidway_returnsPartialResults() throws Exception {
        String m1 = createImage("images/a/1.png");
        String m2 = createImage("images/a/2.png");
        String m3 = createImage("images/a/3.png");
        List<String> paths = List.of(m1, m2, m3);
        when(descRepo.findAll(paths)).thenReturn(Map.of());

        // maxConcurrentLlmCalls=3(setUp)이라 셋 다 거의 동시에 시작된다. 정확히 어느 이미지가
        // "빠른" 쪽이 되는지는 스레드 스케줄링에 달려 있어 예측하지 않고, "정확히 1건만 즉시
        // 끝나고 나머지 2건은 오래 블로킹"이라는 성질만 결정론적으로 보장한다(호출 도착 순서
        // 기준 원자적 카운터).
        AtomicInteger callIndex = new AtomicInteger(0);
        when(visionService.describe(any(), anyString())).thenAnswer(inv -> {
            if (callIndex.getAndIncrement() == 0) return "빠른 설명";
            Thread.sleep(5000);
            return "느린 설명";
        });

        AtomicBoolean skip = new AtomicBoolean(false);
        long start = System.nanoTime();
        Map<String, String> result = service.describeIfNeeded(paths,
                (done, total) -> { if (done == 1) skip.set(true); }, // 첫 완료 즉시 skip
                skip::get);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(result).hasSize(1);
        assertThat(result.values()).containsExactly("빠른 설명");
        assertThat(elapsedMs).isLessThan(4000); // 5초 블로킹 호출들을 기다리지 않았음을 확인
    }

    @Test
    @DisplayName("스킵되지 않으면(skipRequested가 계속 false) 기존처럼 전부 완료될 때까지 기다린다")
    void neverSkipped_waitsForAllCompletions() throws Exception {
        String miss = createImage("images/a/1.png");
        when(descRepo.findAll(List.of(miss))).thenReturn(Map.of());
        when(visionService.describe(any(), anyString())).thenReturn("설명");

        Map<String, String> result = service.describeIfNeeded(List.of(miss), null, () -> false);

        assertThat(result.get(miss)).isEqualTo("설명");
    }
}
