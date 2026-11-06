package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.service.CrossEncoderReranker.Ranked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 {@code llama-server --reranking} 에 대고 보는 점검 — <b>기본으로는 건너뛴다</b>.
 * {@code mvn test -Dtest=CrossEncoderRerankerLiveTest -Drerank-live.url=http://localhost:28095} 로만 돈다.
 * 단위 테스트(내장 서버)가 응답 모양을 가정해 만든 것이라, 서버 버전을 올리거나 모델을 바꿨을 때 이 테스트로 가정을 다시 확인한다.
 */
@EnabledIfSystemProperty(named = "rerank-live.url", matches = ".+")
class CrossEncoderRerankerLiveTest {

    private static Document doc(String id, String text) {
        return Document.builder().id(id).text(text).metadata(Map.of("doc_id", id)).build();
    }

    @Test
    @DisplayName("실제 서버 — 한국어 질의에 관련 있는 문서가 1위, 점수는 내림차순")
    void ranksTheRelevantChunkFirst() {
        String url = System.getProperty("rerank-live.url");
        AppProperties props = new AppProperties("./data", 2, 800, 100, 100, 10, 0.0, true, 0, false, true, false, 3,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, new AppProperties.RerankerConfig(url, null, null, null, null));
        var reranker = new CrossEncoderReranker(props);
        List<Document> candidates = List.of(
                doc("log", "로그는 onlinelog.level 로 레벨을 정하고 파일에 날짜별로 남는다."),
                doc("batch", "배치 프로그램 클래스는 AbstractBatchProgram 을 상속받아 작성한다. beforeExecute 에서 오류가 나면 배치가 중단된다."),
                doc("tx", "온라인과 배치는 트랜잭션 처리 방식이 다르다. 온라인은 서비스 단위로 커밋한다."),
                doc("ui", "화면 레이아웃은 12칼럼 그리드를 쓴다."));

        List<Ranked> out = reranker.rerank("배치 프로그램은 어떤 클래스를 상속받아 작성하나?", candidates, 3);

        System.out.println("[rerank-live] " + out.stream().map(r -> r.doc().getId() + "=" + r.score()).toList());
        assertThat(out).hasSize(3);
        assertThat(out.get(0).doc().getId()).isEqualTo("batch");
        assertThat(out).extracting(Ranked::score).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }
}
