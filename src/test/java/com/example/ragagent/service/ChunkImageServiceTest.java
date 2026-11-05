package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 청크 편집의 그림 추가·교체·삭제·설명 — 경로 검사와 Vision 연결. */
class ChunkImageServiceTest {

    private static final String A = "images/chunks/aaaa.png";
    private static final String B = "images/chunks/bbbb.png";

    @TempDir Path dataDir;
    private CuratedImageStore store;
    private VisionDescriptionService vision;
    private ChunkImageService service;

    @BeforeEach
    void setUp() throws Exception {
        store = mock(CuratedImageStore.class);
        vision = mock(VisionDescriptionService.class);
        AppProperties props = mock(AppProperties.class);
        when(props.dataDir()).thenReturn(dataDir.toString());
        when(store.isResolvable(A)).thenReturn(true);
        when(store.isResolvable(B)).thenReturn(true);
        Files.createDirectories(dataDir.resolve("images/chunks"));
        Files.write(dataDir.resolve(A), new byte[]{1, 2, 3});
        Files.write(dataDir.resolve(B), new byte[]{4, 5, 6});
        service = new ChunkImageService(store, vision, props);
    }

    private static ChunkImageService.EditRequest req(String action, String text, Integer index, String path,
                                                     Integer offset, Boolean describe) {
        return new ChunkImageService.EditRequest(action, text, index, path, offset, describe);
    }

    @Test
    @DisplayName("추가+설명: 그림이 커서 위치에 들어가고 Vision 설명이 그 마커에 붙는다")
    void insertWithDescription() {
        when(vision.describe(any(byte[].class), eq("image/png"))).thenReturn("순서도: 로그인 흐름");

        ChunkImageService.EditResult r = service.edit(req("insert", "앞뒤", null, A, 1, true));

        assertThat(r.text()).isEqualTo("앞\n\n[이미지: " + A + "]\n[이미지 설명: 순서도: 로그인 흐름]\n\n뒤");
        assertThat(r.warning()).isNull();
        assertThat(r.markers()).singleElement().satisfies(m -> {
            assertThat(m.path()).isEqualTo(A);
            assertThat(m.url()).isEqualTo("/api/v1/" + A);
            assertThat(m.exists()).isTrue();
            assertThat(m.described()).isTrue();
        });
    }

    @Test
    @DisplayName("추가, 설명 끔: Vision 을 부르지 않는다")
    void insertWithoutDescriptionSkipsVision() {
        ChunkImageService.EditResult r = service.edit(req("insert", "본문", null, A, 2, false));

        assertThat(r.text()).isEqualTo("본문\n\n[이미지: " + A + "]");
        verify(vision, never()).describe(any(byte[].class), anyString());
    }

    @Test
    @DisplayName("설명 생성이 실패해도 그림은 들어가고 경고가 따라온다")
    void descriptionFailureKeepsTheImage() {
        when(vision.describe(any(byte[].class), anyString())).thenReturn("[이미지 설명 불가: Vision 프로바이더 미등록]");

        ChunkImageService.EditResult r = service.edit(req("insert", "본문", null, A, 2, true));

        assertThat(r.text()).contains("[이미지: " + A + "]").doesNotContain("[이미지 설명");
        assertThat(r.warning()).contains("설명을 만들지 못했습니다");
    }

    @Test
    @DisplayName("교체: 경로가 바뀌고 옛 설명이 지워지며, 켜 두었으면 새 설명이 붙는다")
    void replaceWithNewDescription() {
        when(vision.describe(any(byte[].class), anyString())).thenReturn("표: 환경별 URL");
        String text = "[이미지: " + A + "]\n[이미지 설명: 순서도: 옛]\n뒤";

        ChunkImageService.EditResult r = service.edit(req("replace", text, 0, B, null, true));

        assertThat(r.text()).isEqualTo("[이미지: " + B + "]\n[이미지 설명: 표: 환경별 URL]\n뒤");
    }

    @Test
    @DisplayName("삭제: 마커와 설명이 빠진다")
    void remove() {
        ChunkImageService.EditResult r = service.edit(req("remove", "앞\n[이미지: " + A + "]\n[이미지 설명: x]\n뒤", 0, null, null, null));

        assertThat(r.text()).isEqualTo("앞\n뒤");
        assertThat(r.markers()).isEmpty();
    }

    @Test
    @DisplayName("설명 생성(describe): 이미 있는 설명도 새로 만든 것으로 바꾼다")
    void describeRegenerates() {
        when(vision.describe(any(byte[].class), anyString())).thenReturn("IDE 화면: UserService.java");

        ChunkImageService.EditResult r = service.edit(req("describe",
                "[이미지: " + A + "]\n[이미지 설명: 일반적인 옛 설명]", 0, null, null, null));

        assertThat(r.text()).isEqualTo("[이미지: " + A + "]\n[이미지 설명: IDE 화면: UserService.java]");
    }

    @Test
    @DisplayName("허용되지 않았거나 없는 경로는 마커가 될 수 없다")
    void rejectsUnresolvablePaths() {
        assertThatThrownBy(() -> service.edit(req("insert", "본문", null, "../../etc/passwd", 0, false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("사용할 수 없는 이미지 경로");
        assertThatThrownBy(() -> service.edit(req("replace", "[이미지: " + A + "]", 0, "images/chunks/none.png", null, false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("알 수 없는 작업·없는 순번·과대 본문은 거절한다")
    void rejectsBadRequests() {
        assertThatThrownBy(() -> service.edit(req("explode", "본문", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("알 수 없는 작업");
        assertThatThrownBy(() -> service.edit(req("remove", "본문", 0, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.edit(req("remove", "가".repeat(ChunkImageService.MAX_TEXT_CHARS + 1), 0, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("너무 깁니다");
        assertThatThrownBy(() -> service.edit(req(null, "본문", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("마커 목록: 파일이 없거나 (변환불가) 인 마커는 exists=false")
    void markersReportMissingFiles() {
        var views = service.markers("[이미지: " + A + "]\n[이미지: images/chunks/none.png]\n[이미지(변환불가): images/x/y.emf]");

        assertThat(views).extracting(ChunkImageService.MarkerView::exists).containsExactly(true, false, false);
        assertThat(views.get(2).unconvertible()).isTrue();
    }
}
