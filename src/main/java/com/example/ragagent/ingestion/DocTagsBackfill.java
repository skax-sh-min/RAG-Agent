package com.example.ragagent.ingestion;

import com.example.ragagent.model.TagUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 태그의 출처가 {@code chunk_fts.doc_tags} 에서 {@code doc_registry.tags} 로 옮겨지기 전에
 * 등록된 문서들의 태그를 registry 로 한 번 복사한다.
 *
 * <p>{@link ChunkOverlapBackfill} 과 같은 자리·같은 성격이며, 멱등성도 같은 방식으로 얻는다:
 * {@code tags} 가 {@code NULL} 인 행만 대상이고, 채우고 나면(태그가 없던 문서는 빈 문자열로)
 * 다시 잡히지 않는다. 그래서 재기동은 무해하고, 현재 빌드가 인덱싱한 문서는 이미 자기 태그를
 * 기록하므로 여기 걸리지 않는다.
 *
 * <p><b>이 클래스가 {@code chunk_fts} 태그 컬럼을 읽는 마지막 코드다.</b> 옮긴 뒤로 그 컬럼은
 * 검색 결과에 태그를 동행시키는 사본으로만 남는다({@code CHUNK_ROW_MAPPER} →
 * {@code MetaKey.TAGS} → {@code filterByTags}).
 *
 * <p>실패해도 기동을 막지 않는다 — 최악의 결과는 옛 문서의 태그가 목록에서 비어 보이는 것이고,
 * 태그를 다시 지정하면 복구된다. 다만 그 상태로 남으면 다음 기동에서 또 시도한다.
 */
@Component
public class DocTagsBackfill {

    private static final Logger log = LoggerFactory.getLogger(DocTagsBackfill.class);

    private final DocRegistry docRegistry;
    private final KeywordSearchRepository keywordRepo;

    public DocTagsBackfill(DocRegistry docRegistry, KeywordSearchRepository keywordRepo) {
        this.docRegistry = docRegistry;
        this.keywordRepo = keywordRepo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        try {
            List<String> pending = docRegistry.docIdsWithoutTags();
            if (pending.isEmpty()) return;

            Map<String, List<String>> fromFts = keywordRepo.tagsByDocIds(pending);
            int tagged = 0;
            for (String docId : pending) {
                List<String> tags = fromFts.getOrDefault(docId, List.of());
                // 태그가 없던 문서도 빈 문자열로 기록한다 — NULL 로 두면 다음 기동에서 또 훑는다.
                docRegistry.updateTags(docId, DocRegistry.SHARED, TagUtils.toMetaValue(tags));
                if (!tags.isEmpty()) tagged++;
            }
            log.info("[REGISTRY] 태그 백필 완료 — 문서 {}건 확인, {}건에 태그 기록 (출처: chunk_fts → doc_registry)",
                    pending.size(), tagged);
        } catch (Exception e) {
            log.warn("[REGISTRY] 태그 백필 실패 (무시하고 계속): {}", e.getMessage());
        }
    }
}
