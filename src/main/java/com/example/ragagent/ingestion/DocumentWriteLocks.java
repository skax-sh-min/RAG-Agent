package com.example.ragagent.ingestion;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 문서 하나의 <b>청크를 쓰는 작업끼리의 상호 배제</b>(§6.33 4b) — 같은 문서의 {@code ↺ 재인덱싱}({@link DocumentIndexer#reindexFromMd})과 유지보수 수정안
 * 적용({@code ChangeApplyService})이 겹치지 않게 한다. 겹치면 재인덱싱이 청크 id 를 모두 새로 만드는 동안 적용이 옛 id 를 고쳐 쓰거나(그 수정은 사라지고
 * 일부는 {@code stale}), 적용이 청크를 바꾸는 사이에 재인덱싱이 변환 MD 를 읽어 반쯤 바뀐 문서가 된다.
 *
 * <p><b>기다리지 않고 거절한다</b>({@link #tryAcquire}) — 둘 다 오래 걸리는 작업이라 기다리게 하면 요청 스레드·워커가 쌓이고, 사용자는 어느 쪽이 진행 중인지
 * 알려 주는 편이 낫다. 같은 JVM 안의 잠금이다(앱은 한 프로세스 — SQLite 단일 파일 전제).
 *
 * <p><b>재진입하지 않는다</b> — 이 문서가 "쓰이는 중"이라는 사실은 부르는 스레드가 누구든 같다. {@code ReentrantLock} 은 같은 스레드가 다시 잡으면 통과시켜,
 * 한 스레드가 겹치는 두 작업을 중첩해 부르는 실수를 막지 못한다(처음 구현이 그랬고, 같은 스레드에서 잠금을 쥔 채 부르는 테스트가 잡았다).
 */
@Component
public class DocumentWriteLocks {

    private final Set<String> held = ConcurrentHashMap.newKeySet();

    /** 잠금을 잡았다는 증표 — {@code try-with-resources} 로 푼다. 두 번 닫아도 한 번만 푼다(다른 작업의 잠금을 풀지 않게). */
    public final class Lease implements AutoCloseable {
        private final String docId;
        private final java.util.concurrent.atomic.AtomicBoolean open = new java.util.concurrent.atomic.AtomicBoolean(true);

        private Lease(String docId) {
            this.docId = docId;
        }

        @Override
        public void close() {
            if (open.compareAndSet(true, false)) held.remove(docId);
        }
    }

    /**
     * @return 잠금을 잡았으면 {@link Lease}, 이 문서에 다른 쓰기 작업이 진행 중이면 {@code null}
     */
    public Lease tryAcquire(String docId) {
        return held.add(docId) ? new Lease(docId) : null;
    }

    /** 지금 이 문서에 쓰기 작업이 진행 중인가 — 화면 안내·테스트용. 잠그지 않는다. */
    public boolean isLocked(String docId) {
        return held.contains(docId);
    }
}
