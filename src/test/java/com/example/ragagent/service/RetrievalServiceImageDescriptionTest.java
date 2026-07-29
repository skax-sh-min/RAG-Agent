package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.context.MessageSource;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — RetrievalService skips Lazy Vision for images whose description is already embedded in
 * the chunk text at indexing time (bug: MarkdownCorrectionService's inline "[이미지 설명: ...]"
 * text is never persisted to image_descriptions, so every such image looked like a cache miss on
 * every turn that retrieved it).
 */
class RetrievalServiceImageDescriptionTest {

    @Nested
    @DisplayName("hasEmbeddedDescription()")
    class HasEmbeddedDescription {

        @Test
        @DisplayName("마커 바로 뒤에 설명이 있으면 true")
        void trueWhenDescriptionFollowsMarker() {
            String text = "본문\n[이미지: images/a/1.png]\n[이미지 설명: 다이어그램입니다.]\n다음 본문";
            assertThat(RetrievalService.hasEmbeddedDescription(text, "images/a/1.png")).isTrue();
        }

        @Test
        @DisplayName("마커만 있고 설명이 없으면 false")
        void falseWhenNoDescriptionFollows() {
            String text = "본문\n[이미지: images/a/1.png]\n다음 본문";
            assertThat(RetrievalService.hasEmbeddedDescription(text, "images/a/1.png")).isFalse();
        }

        @Test
        @DisplayName("마커 자체가 없으면 false")
        void falseWhenMarkerAbsent() {
            String text = "이미지 마커가 전혀 없는 본문입니다.";
            assertThat(RetrievalService.hasEmbeddedDescription(text, "images/a/1.png")).isFalse();
        }

        @Test
        @DisplayName("다른 이미지의 설명은 이 경로에 영향을 주지 않는다")
        void falseForDifferentImagePath() {
            String text = "[이미지: images/a/OTHER.png]\n[이미지 설명: 다른 이미지 설명]\n"
                    + "[이미지: images/a/1.png]\n본문만 있고 설명 없음";
            assertThat(RetrievalService.hasEmbeddedDescription(text, "images/a/1.png")).isFalse();
        }

        @Test
        @DisplayName("null 텍스트는 false")
        void falseForNullText() {
            assertThat(RetrievalService.hasEmbeddedDescription(null, "images/a/1.png")).isFalse();
        }
    }

    @Nested
    @DisplayName("execute() — LazyVisionService 호출 여부")
    class ExecuteIntegration {

        private RagService rag;
        private LazyVisionService lazyVision;
        private RetrievalService svc;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setup() {
            AppProperties props = mock(AppProperties.class);
            when(props.searchTopKSafe()).thenReturn(7);
            when(props.searchMultiqueryEnabledSafe()).thenReturn(false);
            when(props.searchMultiqueryMinLengthSafe()).thenReturn(0);
            when(props.searchHybridEnabledSafe()).thenReturn(false);
            when(props.searchRetryEscalateSafe()).thenReturn(false);
            when(props.searchRerankEnabled()).thenReturn(false);
            when(props.searchCandidateMultiplierSafe()).thenReturn(3);
            when(props.searchTagCandidateMultiplierSafe()).thenReturn(2);

            rag = mock(RagService.class);
            lazyVision = mock(LazyVisionService.class);
            LlmRouter llmRouter = mock(LlmRouter.class);
            LlmProvider expansionProvider = new LlmProvider(
                    "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", true, mock(ChatModel.class), null);
            when(llmRouter.routeProviderWithFallback(any(), any())).thenReturn(expansionProvider);
            MessageSource messageSource = mock(MessageSource.class);
            when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("{query} {number}");

            svc = new RetrievalService(llmRouter, mock(LlmUsageRepository.class), rag, props,
                    Optional.of(lazyVision), Optional.empty(), messageSource);
        }

        private static Document docWithImage(String id, String imagePath, boolean withEmbeddedDesc) {
            Map<String, Object> m = new HashMap<>();
            m.put(MetaKey.DOC_ID, id);
            m.put(MetaKey.CHUNK_INDEX, 0);
            m.put(MetaKey.IMAGE_PATHS, imagePath);
            String text = "본문\n[이미지: " + imagePath + "]\n"
                    + (withEmbeddedDesc ? "[이미지 설명: 이미 인덱싱 시점에 생성된 설명]\n" : "")
                    + "이어지는 본문";
            return new Document(text, m);
        }

        private static AgentState state() {
            return AgentState.of("질문", "latest", "t1", "anonymous", "", RoutingMode.COST_FIRST, false, Locale.KOREAN);
        }

        @Test
        @DisplayName("청크에 설명이 이미 있으면 LazyVisionService를 호출하지 않는다")
        void skipsLazyVisionWhenDescriptionAlreadyEmbedded() {
            Document d = docWithImage("d1", "images/a/1.png", true);
            when(rag.searchBatch(any(), any(), any(), anyInt())).thenReturn(List.of(List.of(d)));

            AgentState result = svc.execute(state());

            verify(lazyVision, never()).describeIfNeeded(any(), any(BiConsumer.class));
            // 텍스트도 그대로 유지되어야 한다 — 중복 "설명:" 추가 없음
            assertThat(result.retrievedDocs().get(0).getText()).contains("이미 인덱싱 시점에 생성된 설명");
            assertThat(result.retrievedDocs().get(0).getText().split("이미지 설명", -1)).hasSize(2); // 딱 1회만
        }

        @Test
        @DisplayName("청크에 설명이 없으면 LazyVisionService를 정상 호출한다")
        void callsLazyVisionWhenNoEmbeddedDescription() {
            Document d = docWithImage("d1", "images/a/1.png", false);
            when(rag.searchBatch(any(), any(), any(), anyInt())).thenReturn(List.of(List.of(d)));
            when(lazyVision.describeIfNeeded(any(), any(BiConsumer.class)))
                    .thenReturn(Map.of("images/a/1.png", "새로 생성된 설명"));

            AgentState result = svc.execute(state());

            verify(lazyVision).describeIfNeeded(eqList("images/a/1.png"), any(BiConsumer.class));
            assertThat(result.retrievedDocs().get(0).getText()).contains("새로 생성된 설명");
        }

        @Test
        @DisplayName("이미지 2개 중 1개만 설명이 있으면 나머지 1개만 분석 대상으로 넘긴다")
        void mixedBatch_onlyAnalyzesMissingOne() {
            Map<String, Object> m = new HashMap<>();
            m.put(MetaKey.DOC_ID, "d1");
            m.put(MetaKey.CHUNK_INDEX, 0);
            m.put(MetaKey.IMAGE_PATHS, "images/a/1.png,images/a/2.png");
            String text = "[이미지: images/a/1.png]\n[이미지 설명: 이미 있음]\n"
                    + "[이미지: images/a/2.png]\n본문";
            Document d = new Document(text, m);
            when(rag.searchBatch(any(), any(), any(), anyInt())).thenReturn(List.of(List.of(d)));
            when(lazyVision.describeIfNeeded(any(), any(BiConsumer.class)))
                    .thenReturn(Map.of("images/a/2.png", "새 설명"));

            svc.execute(state());

            verify(lazyVision).describeIfNeeded(eqList("images/a/2.png"), any(BiConsumer.class));
        }

        /** Mockito's List content matcher, kept local to avoid an extra static import clash. */
        private static List<String> eqList(String... items) {
            return org.mockito.ArgumentMatchers.eq(List.of(items));
        }
    }
}
