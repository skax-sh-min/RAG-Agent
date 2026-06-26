package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chroma.vectorstore.ChromaApi;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminService}가 백엔드 독립적으로 동작하는지 검증한다: sqlite-vec 모드에서
 * {@code ChromaApi} 빈이 없어도({@code Optional.empty}) 청크 조회/삭제가 NPE 없이 graceful하게 동작해야 한다.
 */
class AdminServiceTest {

    @Test
    @DisplayName("ChromaApi 없음(sqlite-vec): listCollections → available=false, 조회는 빈 결과, 변경은 no-op")
    void noChromaApi_degradesGracefully() {
        AdminService svc = new AdminService(Optional.empty());

        assertThat(svc.listCollections().available()).isFalse();
        assertThat(svc.listCollections().items()).isEmpty();
        assertThat(svc.getChunks("c", null, 0, 10)).isEmpty();
        assertThat(svc.getChunk("c", "id")).isNull();
        assertThat(svc.countChunks("c", null)).isZero();
        // 변경 연산은 예외 없이 무시
        assertThatCode(() -> svc.deleteChunk("c", "id")).doesNotThrowAnyException();
        assertThatCode(() -> svc.updateChunk("c", "id", "t", Map.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ChromaApi 있음(chroma): listCollections가 ChromaApi에 위임")
    void withChromaApi_delegates() {
        ChromaApi api = mock(ChromaApi.class);
        when(api.listCollections(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of());
        AdminService svc = new AdminService(Optional.of(api));

        AdminService.CollectionsResult r = svc.listCollections();

        assertThat(r.available()).isTrue();
        verify(api).listCollections(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("ChromaApi 없음: deleteChunk가 ChromaApi 접근 시도조차 안 함")
    void noChromaApi_deleteDoesNotTouchApi() {
        // Optional.empty() → 내부 chromaApi == null; 호출해도 아무 상호작용 없음 (NPE 없음)
        AdminService svc = new AdminService(Optional.empty());
        assertThatCode(() -> svc.deleteChunk("c", "id")).doesNotThrowAnyException();
    }
}
