# RAG-Agent 온라인 확장 개발 계획

> Java 개발자 관점 · Spring Boot 3.5 + Spring AI 1.1 + Java 21 · 작성일 2026-05-11  
> **개발 기준 문서**: 이 파일(documents/PLAN.md)이 마스터. 살아 있는 동작 명세는 코드와 [CLAUDE.md](../CLAUDE.md), 결정 근거·실패 기록은 [PITFALLS.md](PITFALLS.md), 생각 수준 실측 결과는 [THINKING_EVALUATION.md](THINKING_EVALUATION.md).

---

## 📊 전체 현황 대시보드

> 완료/미착수를 한눈에 보도록 상단 대시보드를 신설했다.
>
> **문서 분리 (2026-09-02 적용)**: 완료 절의 «유지해야 할 결정»·«계획이 틀렸던 지점»과 해소된 이슈는 [PITFALLS.md](PITFALLS.md) 로 옮겼다. 이 파일은 **앞으로 할 일과 이미 한 일의 요약**을 담고, **왜 그렇게 했고 무엇이 틀렸는지**는 그쪽이다 — 계획을 볼 때와 코드를 고칠 때 필요한 것이 다르기 때문이다.

> **완료 항목 압축 원칙 (2026-08-22 적용)**: ✅ 완료된 항목은 **① 무엇을 왜 그렇게 했는가 ② 앞으로도 지켜야 할 결정·함정 ③ 남은 열린 항목**만 남기고, 구현 과정 서술·테스트 통계·CLAUDE.md에 이미 있는 메커니즘 설명은 걷어낸다. 살아 있는 동작 명세의 단일 출처는 **코드와 CLAUDE.md**이고, 이 문서는 "왜 그렇게 결정했는가"의 기록이다 — 양쪽에 같은 설명을 두면 드리프트만 생긴다(§12가 의존성에 대해 같은 판단을 이미 적용했다).

### ✅ 완료 — Phase 1·2·5·6·7 전체, Phase 3 전체(§6.16.2·6.19.1·6.20 제외)

> **2026-10-14 ~ 10-19 추가 완료**: **시각의 단일 규칙**(§6.25 열린 항목 해소) — DB 에 쓰는 시각은 전부 UTC(`DbTimestamp`, 옛 서버 로컬 값은 Flyway V9 가 옮겼다) · 사람이 읽는 서버 시계(로그·감사 로그 회전·백업 cron·파일 이름)는 `app.time-zone`(기본 `Asia/Seoul`)이고 JVM 기본 시간대는 읽지 않는다 · 화면의 시각은 UTC 로 내보내 브라우저가 보는 사람의 시간대로 바꾼다(`UtcTime`·`localizeTimes()`) · 질문 추천에 날짜와 출처 이모지 배지. **수정**: 채팅 화면 경로(`/ui/chat/stream`)가 질문 길이 제한(2,000자)을 건너뛰던 것(17,500자 질문이 그대로 들어가 답변·검증 프롬프트가 문서를 덜어냈다) · 질문 추천이 입력 전문을 URL 에 실어 긴 붙여넣기에서 요청 줄 한도(8KB)를 넘던 것(앞 200자만 보낸다) · 채팅 스트리밍의 컨텍스트 초과가 안내 문구 대신 서버 오류 원문으로 나가고 창 재탐지를 걸지 않던 것(§6.26) · `/settings` 생각 수준 카드가 관측이 쌓이면 렌더에 실패하던 것(`#messages.msg` 는 인자 3개까지). **문서 정리**: 완료 절 §6.15·§6.19·§6.25·§6.27·§6.29·§10.11~10.13 재압축(옮겨야 할 결정은 먼저 PITFALLS 로 — 전문은 커밋 `c9a9ec4`) · §10.10 열린 항목 (a)(c) 해소 반영.
>
> **2026-10-13 추가 완료**: **후속 질문 독립화 재측정과 결정**(§10.12 (d)(e), [SEARCH_EVALUATION.md §3.4](SEARCH_EVALUATION.md)) — 검색 하네스가 재실행마다 같은 대화 스레드에 앞 질문을 쌓던 결함을 고치고, 앞 질문을 섞으면 손해를 보는 **주제 전환 질문 7개**를 더해 21문항으로 다시 쟀다. 독립화는 원문보다 1~2건 낫고 주제 전환에서 손해가 없으며, 대안(앞 질문 이어 붙이기 · 원문과 함께 검색 · 되풀이 가드)은 이기지 못했다. 10-12 의 "독립화는 거의 이득이 없고 이어 붙이기가 낫다"를 철회하고 **독립화를 그대로 두기로 결정**(열린 항목 (e) 닫음). **코드 검토에서 나온 수정 셋**: 키워드(BM25) 축으로만 찾힌 청크가 원문 대신 검색용 파생 텍스트(파일명·LLM 맥락 요약 + 장식을 걷은 본문)로 답변·검증·출처 미리보기에 가던 버그(최종 컷에서 원문으로 되돌림 — 운영 설정 골든셋 26문항 중 6번이 해당) · **키워드 검색어에 조사·어미를 뗀 어간을 더함**(§10.7 — 골든셋 두 구성 모두 1.000, nDCG `.env` 0.803 → 0.844 · 운영 0.811 → 0.902, 늘 놓치던 arch-11 회복) · 대화 요약의 중복 방지 기록이 줄지 않던 것(15초 뒤 사라지는 캐시로). **같은 날 기능**: 유사도 임계값이 벡터 결과를 너무 많이 거르면 한 번만 0.05 낮춘다(`SimilarityFallback`) · 큐레이션 축의 문턱을 따로 둔다(`app.search-curated-qa-min-similarity` — §10.10 열린 항목 (a) 해소) · 추가 질문 개수 설정(2~5)과 대화별 저장(Flyway V8 `thread_follow_up`) · SQLite 정기 백업(`DatabaseBackupService`, 아래 진행할 것 4번) · `/settings` 항목 라벨·설명 풍선·카드 순서.
>
> **2026-10-12 추가 완료**: **로컬 LLM 이중화 마무리**(§6.12 ⑦ — 임베딩 엔드포인트 장애 전환 · 복제본 한 대가 꺼져 있어도 기동) · **짧은 호출의 출력 상한**(§6.29 열린 항목 (d) — 분류·리랭크·제목·요약·이미지 둘이 프로바이더 기본값(12,000) 대신 자기 상한을 싣는다) · **검증 실패 재시도의 조기 종료**(§6.27 — 같은 게이트·같은 청크로 두 번 반려되면 멈춘다, PROGRESSIVE 는 곧바로 업그레이드). 정리: 추천의 도달 불가 `Scope.ME` 축 제거(§10.15) · 고아 출처·이미지 참조 행 정리(Flyway V7, §6.25) · 문서 드리프트(§6.29 출하값 서술, 10-07~08 완료 기록, 기본 `max-tokens` 12,000 반영). **측정**([SEARCH_EVALUATION.md](SEARCH_EVALUATION.md) · [THINKING_EVALUATION.md §3.7](THINKING_EVALUATION.md)): 골든셋 recall@10 0.962 / 운영 설정 1.000 으로 7월 기준선에서 회귀 없음(그 전에 낡은 정답 문자열 6개를 바로잡았다, §10.10 (d) · §10.11 (d) 해소) · 질의 확장은 1건(운영 설정)만 되찾지만 **켠 채 유지**(§10.7) · 후속 질문 독립화의 첫 측정(그날의 "거의 이득 없음" 결론은 하네스 결함 위라 10-13 항목에서 철회), 게이트 임계값은 **그대로**(§10.12 (a) 닫음) · **생각 토큰 예산은 줄인 만큼 검증의 탐지를 없앤다** — **구현하지 않기로 결정**(§6.29 (a) 닫음).
>
> **2026-10-08 추가 완료**: **§6.12 ⑥ 로컬 서버 장애 전환** — 같은 로컬 서버 두 대 중 먼저 등록된 쪽이 죽어도 채팅이 다른 서버로 넘어간다(연결 타임아웃을 차단 대상으로 · 채팅 답변 스트리밍은 첫 토큰 전 `StreamFailover` · 질의 확장은 호출마다 라우팅하는 `RoutedChatModel`) · **`/settings` 프로바이더 표** — 상태 열(미설정·접속불가·정상)을 모델 목록 API 를 실제로 불러 채우고(`ProviderConnectivity`, 페이지는 "확인 중"으로 먼저 열린다), 생각 제어 열을 세 상태(사용자 설정 적용·서버 설정 사용·Unknown)로, 생각 수준 카드는 접힌 채 시작.
>
> **2026-10-07 출하값 변경**(운영 결정): `app.llm.temperature` 0.0 → 0.2 · `direct-temperature` 0.1 → 0.5 · `max-tokens` 10,000 → 12,000(대화 이력 예산 5,000 → 6,000자, 블로킹 N/C 답변 예약 7,000 → 8,400 이 따라 움직인다) · 생각 수준 22곳 전부 끔. 커밋 `390a8a4`.
>
> **2026-10-07 추가 완료**: **§6.29 호출 지점별 생각(추론) 수준 제어**(1~6단계 전부) — LLM 호출 22곳마다 생각을 끔/낮게/중간/높게로 정하고 `/settings`(관리자 전용)에서 핫 편집한다. 호출부는 사이트만 표시하고(`ThinkingControl.mark`) 프로바이더가 정해진 뒤 데코레이터가 dialect 로 바꿔 싣는다(llama.cpp 는 켬/끔). 채팅 답변 스트리밍은 `AnswerStreamer` 하나가 같은 규칙을 싣고, 생각이 켜지면 출력 예약에 수준만큼 여유를 더하되 기본 예약은 깎지 않는다(`ThinkingBudget`). 카드는 22곳 × 4수준의 예약·입력 예산·문서 수·생각 자리·최악 소요를 **런타임이 쓰는 함수 그대로** 미리 계산한다. **6단계 실측**(llama.cpp b11433 + gemma-4-E2B, 같은 입력을 끔/낮게로 번갈아 부름)으로 출하값을 정했다 — 생각이 이득을 준 곳은 **응답 검증**(근거 없는 답을 잡는 비율 6% → 44%)과 **응용(C) 답변의 코드**뿐이라 그 둘만 낮게, 나머지 15곳은 끔(같은 품질에 10~40배 느렸다 — 권고값; 출하값은 이후 22곳 전부 끔으로 바뀌었다). 곁에서 나온 것: 채팅 답변 스트리밍의 축소 재시도가 실서버에서 한 번도 발동하지 않던 버그(WebClient 오류 본문) · `settings.html` 의 툴팁 스크립트가 페이지에 실리지 않던 버그 · Direct 블로킹 이력 안전망의 예약 불일치. 결과는 [THINKING_EVALUATION.md](THINKING_EVALUATION.md)(실측 보고서 — 환경·방법·표·재현 방법·테스트 결과). 남은 것: 요청 단위 생각 토큰 예산(b11433 이 받는다 — 구현 여부는 사용자 결정) · 측정 못 한 4곳(요약·이미지)
>
> **2026-09-22 추가 완료**: **§6.28 로컬 LLM 장애 가시성**(`GET /api/v1/llm/ping` 3단계 생사 확인 · 헤더 `차단 중 Ns` · 연속 3회 실패 문구 전환 · 사전 future 의 `CompletionException` 풀기 · traceId 를 워커·future·인덱싱 중첩 실행기까지 전파) · **§10.15 질문 추천 개선**(Direct 제외 · 재사용 턴의 모양 복사 · 활성 출처 사전 필터 + 10분 부정 캐시 · 내용어 AND 매칭 · 상한 20) · 코드 블록 이중 축소 제거
>
> **2026-09-07 추가 완료**: **§6.19.4 폐쇄망 배포 실사 하드닝**(CORS 와일드카드 제거 · REST 문서 쓰기 게이트 · full-auth 문서 RBAC + `/setup` 부트스트랩 · 공유 게스트 레이트리밋 키 · 게스트 이미지 업로드 버킷·주기 정리 · 감사 IP·trace id·세션 위생 · 비-root 컨테이너) · **성능/기능 4건**: `chunk_fts` 를 청크 id 로 찾던 **코퍼스 전체 스캔 제거**(동반 키 테이블 `chunk_fts_key` — 대화 열기·턴 저장·재사용 검증·원문 보기·신고가 전부 여기 걸려 있었고, 같은 커밋에서 Chroma 배포의 `vec_document_chunks` 조인 실패도 함께 해결) · 재사용 답변이 새 대화의 첫 메시지일 때 `thread_meta` 미생성으로 **대화가 사라지던 버그** · PPTX 도형 래스터라이즈 픽셀 상한(슬라이드 밖 앵커 하나가 수 GB 할당) · 관리자 청크 편집의 메타데이터 병합(임의 키 주입 차단 + Chroma 경로 메타데이터 유실 수정) · **큐레이션 축의 요약·키워드와 BM25 색인**: 승인된 지식 제안이 `/admin` 청크 화면에서 그 두 칸이 늘 비어 있던 것을 "화면을 채우는" 쪽으로 고치지 않고 **읽는 경로를 먼저 만들었다** — 큐레이션 청크를 `chunk_fts` 에 색인하고, `RetrievalService.curatedAxis()` 가 예약 네임스페이스로 BM25 를 한 번 더 물어 **자기 축 안에서** 벡터 결과와 합친다(키워드 축으로 보내면 두 가중치를 동시에 받아 이중 가산). 그 위에 지식 제안 폼의 요약·키워드 입력란과 "빈 칸 자동 생성"(LLM 1회, 채워진 칸은 서버가 지킨다)을 얹었다. 곁에서 나온 버그 하나 — `/admin` 청크 재인덱싱이 큐레이션 청크를 문서 청크 규칙으로 태워 **제목이 빠진 채 재임베딩**하고 있었다(`CuratedQaService.reembedRow()` 로 분기).
>
> **2026-09-03 추가 완료**: **§10.13 Direct 턴의 이력 확대**(문서 자리가 비면 그만큼 이력에 돌려준다 — DN 답변의 `## 요약` 유도 · 두 이력 경로의 규칙 통일 · Direct 경로의 예산 안전망) · **§10.12 짧은 후속 질문의 독립화**(재작성 한 번이 검색 축 셋 + 분류기를 함께 고친다 — LLM 호출 순증 0, 재료를 이전 '질문'으로 좁혀 열린 항목 (b) 해소).
>
> **2026-09-02 추가 완료**: **§10.11 좋아요 → 지식 제안 경유**(무검토 유입 경로 제거 — 검색 코퍼스로 들어가는 문이 관리자 승인 하나로 모였다) · §6.27 검증 실패 재시도 개선(피드백 루프 · 게이트별 분기 · 청크 교체 · 여유 기반 증가) · QA 픽스 6건(저장 상한이 삭제로 회수되지 않던 문제, 다중 파일 업로드 429, 업로드 거부 사유 유실, 임시 파일 누수 등).
>
> **2026-09-01 추가 완료**: §6.26 컨텍스트 초과 대응(창 인지 + 입력 예산, 11단계 — 인덱싱 재작성 예산 · 선형 축소 재시도 · 창 재탐지 버튼 · max-tokens 핫 편집까지 · **남은 이슈 없음**) · §6.24 의 남은 이슈였던 토큰 추정 4배 불일치 해소 · C(응용) 모드 운영자 스위치(`app.llm.creative-mode-enabled`) · `/settings` 허용 범위 툴팁과 인덱싱 3종 범위 조정 · `/admin` 문서 레지스트리 페이지네이션 · §6.15 전역 저장 상한(`app.upload.max-total-size`).

| Phase | 완료 항목 | 상세 |
|---|---|---|
| **Phase 1** — 보안 기반 | Step 1.1~1.6 전체(Caddy·Flyway·Spring Security·멀티유저 격리·CSRF·로그인/회원가입 UI) + `app.auth.enabled` no-auth 토글 | §4 |
| **Phase 2** — 모바일 UI | 반응형 레이아웃(Offcanvas) · PWA(manifest/SW/오프라인) · 다크모드·접근성 | §5 |
| **Phase 3** — 운영 견고화 | §6.1 Rate limit · §6.2 업로드 검증(매직바이트) · §6.15 전역 저장 상한 · §6.3 예외처리 · §6.4 감사로그 · §6.5 임베딩 사용량 분리 · §6.6 비활성 프로바이더 표시 · §6.7 orphan 기록 삭제 · §6.8 피드백 기반 컨텍스트 제외 · §6.9 요약 선계산 · §6.10 백그라운드 사용량 분리 · §6.11 컨텍스트 예산 정합성 · §6.12 다중 사용자 동시 LLM 처리(동시성 게이트+백프레셔+로드밸런싱) · §6.13 설정 페이지(핫 수정 오버라이드) · §6.14 핵심 채팅 경로 추적 · §6.16.1 스트리밍/인덱싱 중단 버튼 · §6.17 관리 전용 인증(B안) · §6.18 Direct temperature 분리 · §6.19.2 `/admin`·actuator RBAC · §6.19.3 XFF 신뢰 옵트인 · §6.19.4 폐쇄망 실사 하드닝 · §6.21 소형 LLM 분리+멀티 LLM 처리량 확장 · §6.22 접속자별 채팅 개인화(no-auth) · §6.23 청크 변경 시 답변 재사용 무효화·대화 기록 표시 · §6.24 응답 모드 재설계(S/N/C — L 제거·모드별 전용 프롬프트·오염 방지 3건, `4-c`만 미착수) · §6.25 관리자 대화 목록·삭제·검색 진단 연결 | §6 |
| **Phase 5** — Vector Store | Step 5.1~5.10 전체(Chroma↔sqlite-vec 런타임 전환, 관리자 페이지, 태그 검색, 운영/벡터 DB 분리 — 5.10 은 2026-09-28 단일 파일로 환원) | §8 |
| **Phase 6** — 폐쇄망/노-도커 | G1~G5(키리스 LOCAL·차원 외부화·라우팅 외부화·런북·무외부호출 인수) | §9 |
| **Phase 7** — 검색 품질·성능 고도화 | §10.1~10.9 전체(17건) — 정확도·속도·메모리 개선 + recall@k/nDCG@k 평가 하네스(baseline recall@10=0.962). **+ §10.10** — 좋아요 기반 큐레이션 Q&A 지식화(스냅샷·임베딩·검색 융합·관리 UI) 완료 | §10 |

추가로 Phase 3 초기에 완료된 항목(문서화되지 않았던 픽스 포함): ChromaDB v2 API 컬렉션명→UUID 자동 변환, 문서 저장 경로 공유 구조 단순화(`DocRegistry.SHARED`), 인덱싱 SSE 진행 단계별 표시, 키워드 추출 타임아웃 시 CircuitBreaker 오동작 수정, DOCX 변환 전 구버전 아티팩트 삭제 순서 수정, 환경변수 외부화 4건, 의존성 최신 stable 일괄 업데이트(정확한 버전은 pom.xml 참조).

### 🔵 진행할 것 (우선순위 순)

> **재우선순위화**: 실배포 기준(폐쇄망·no-auth 단일 운영자)에서 가치가 없는 **멀티유저(`auth.enabled=true`) 전용 작업**(§6.19·§6.20·§6.16.2·Phase 4)은 전부 후속으로 내렸다(사유는 아래 후속 표의 트리거 열 참조). ⚠️ **§6.19는 이 판단이 절반만 맞았다** — 2026-09-07 실사에서 no-auth·management-only 조합에서만 성립하는 노출면이 여럿 나와 §6.19.4로 처리했다. 남은 것은 §6.19.1(API CSRF)뿐이고 그것은 여전히 인증 모드 전용이다. §6.15(스토리지 쿼터)는 전역 상한이라 단일 운영자에도 적용되므로 이 그룹에 남겨 두었고, 2026-09-01 완료됐다. **§6.25(관리자 대화 목록)도 이름은 §7.3(Phase 4) 계열이지만 같은 이유로 지금 진행 그룹이다** — `app.auth.guest-identity`가 `shared`가 아니면 no-auth 단일 운영자 배포에도 방문자별 대화가 쌓이고, 그중 Step 1은 인증 모드와 무관한 검색 오염 버그 수정이다.

**🟢 지금 진행 (no-auth 단일 운영자 배포에도 바로 적용)**

| 순위 | 항목 | 현재 상태 |
|---|---|---|
| ~~1~~ | ~~**§10.11 좋아요 → 지식 제안 경유**~~ | ✅ 2026-09-02 완료 (5단계 + 정책 3건 전부) |
| ~~1~~ | ~~**§10.12 짧은 후속 질문의 독립화**~~ | ✅ 2026-09-03 완료 (재작성 → 검색 축 셋 + 분류기, 진단 한 줄, 열린 항목 (b) 해소). 2026-10-13 측정: 원문보다 1~2건 낫고 주제 전환에서 손해 없음 — 대안(이어 붙이기 · 함께 검색 · 되풀이 가드) 대신 그대로 두기로 결정(열린 항목 (e) 닫음) |
| ~~2~~ | ~~**§10.13 Direct 턴의 이력 확대**~~ | ✅ 2026-09-03 완료 (1단계 프롬프트 + 예산/렌더 규칙 + Direct 안전망) |
| ~~3~~ | ~~**§10.14 청크 오류 신고 (사용자 → 관리자)**~~ | ✅ 2026-09-04 완료 (0~5단계 전부 — 렌더러 통일 · 대기열 · 청크 단위 관리자 처리 · 배지) |
| ~~3~~ | ~~**§6.29 호출 지점별 생각(추론) 수준 제어**~~ | ✅ 2026-10-07 완료 (1~6단계 — 사이트 22곳 × 수준 4개, `/settings` 관리자 카드, 실측이 권한 값은 검증·응용 답변만 낮게였으나 출하값은 속도를 택해 22곳 전부 끔). 열린 항목: 측정 못 한 4곳. ~~요청 단위 생각 토큰 예산~~은 2026-10-12 측정(예산을 줄인 만큼 검증의 탐지도 사라진다) 뒤 구현하지 않기로 결정. (d) 짧은 호출의 출력 상한은 2026-10-12 해소 |
| 4 | 운영 준비 잔여 — ~~SQLite 백업 자동화~~, Caddy 인증서 만료 모니터링 | **SQLite 백업은 2026-10-13 완료** — 앱 안의 정기 백업(`DatabaseBackupService`, 매일 03:30 · 최근 7개 · `/admin` "DB 백업" 카드에서 수동 실행). Litestream/cron 대신 앱 안에 둔 이유: 폐쇄망 단일 운영자 배포에 바이너리·스케줄러를 하나 더 들이지 않고, 별도 연결의 `VACUUM INTO` 라 WAL 에서 쓰기를 막지 않으며(54 MB 1초), 사본마다 `quick_check` 로 검증된다. 담지 않는 것: 문서 파일 · Chroma 벡터(OPERATOR_MANUAL §6.3.2). Caddy 인증서 모니터링은 미착수 |
| 5 | §6.24 `4-c` — 검색 부스트 상향 | 부스트 기본값이 0이라 미착수. **§6.26 이후 전제가 바뀌었다** — 검증 발췌 상한이 이제 창에서 파생되므로(`evalExcerptTokenBudget`), 부스트를 올리기 전에 확인할 것은 `MAX_EVAL_EXCERPT_CHARS` 상수가 아니라 **대상 프로바이더의 실제 창**이다 |
| 6 | §9.4 — CADDY 하위호환 별칭 | 선택, 낮은 우선순위 |
| 7 | Phase 2 남은 실기기 검증 2건 (키보드 하단 고정 · 홈 화면 standalone) | 좌우 스크롤·다크모드는 자동 검증 완료, 나머지는 실기기 필요 |

**🟣 후속 — 멀티유저(`auth.enabled=true`) 활성화 시에만 착수**

| 순위 | 항목 | 트리거 |
|---|---|---|
| 1 | **§6.19 보안 하드닝** — API CSRF/세션 혼용(6.19.1) · **로그인 전용 rate-limit 버킷**(§6.1 참조 — 문서에만 있고 코드엔 없던 항목) ※ **6.19.2(`/admin/**` + actuator ROLE_ADMIN 게이트)는 2026-09-06 완료, 6.19.3(XFF)은 §6.22와 함께 완료** | **auth 모드 여는 시점에 반드시 선행**(게이트) — no-auth엔 노출면 없음 |
| 2 | **§6.20 사용자별 LLM 토큰 쿼터** | 실사용자가 여럿 생겨 사용량 격리가 필요해질 때 |
| 3 | **§6.16.2 계정 잠금 상태 피드백** | auth 모드 로그인 UX — no-auth엔 로그인 자체가 없음 |
| 4 | **Phase 4** (조건부) — §7.1 OAuth2 소셜 로그인 · §7.2 PostgreSQL 마이그레이션 · §7.3 관리자 페이지 확장 | §3 트리거 참조(가입 마찰·SQLite 한계 신호·다중 사용자 운영 관리 필요 시) |

> 검색 고도화 **Phase 7-D**(sqlite-vec 단일 스캔·cross-encoder 리랭커·시맨틱 응답 캐시)는 재검토에서 범위 제외(사유·재개 신호는 §10.5) — Phase 7의 유일한 미착수였던 항목.
>
> 스키마 관리: **운영 테이블은 Flyway 로 일원화**(2026-09-29, V4) — 신규 테이블·컬럼·인덱스는 `V5` 이후의 SQL 마이그레이션 파일로 추가한다. 저장소의 런타임 DDL 은 없어졌다(§13).

---

## 목차

1. [요약 (Executive Summary)](#1-요약-executive-summary)
2. [현재 구조 분석](#2-현재-구조-분석)
3. [핵심 기술 의사결정](#3-핵심-기술-의사결정)
4. [Phase 1 — 보안 기반 구축](#4-phase-1--보안-기반-구축--완료)
5. [Phase 2 — 모바일 UI](#5-phase-2--모바일-ui-개선--완료)
6. [Phase 3 — 운영 견고화](#6-phase-3--운영-견고화--일부-완료)
7. [Phase 4 — 확장 (조건부)](#7-phase-4--확장-조건부--미착수)
8. [Phase 5 — Vector Store 선택적 연동](#8-phase-5--vector-store-선택적-연동--완료--확장-계획)
9. [Phase 6 — 폐쇄망 / 노-도커 실행 지원](#9-phase-6--폐쇄망air-gapped--노-도커-실행-지원--g1g5-완료-sqlite-vec-라이브-부팅은-운영-인수)
10. [Phase 7 — 검색 품질·성능 고도화](#10-phase-7--검색-품질성능-고도화--완료)
11. [리스크 및 이슈](#11-리스크-및-이슈)
12. [의존성 변경 사항](#12-의존성-변경-사항-pomxml)
13. [DB 스키마 변경](#13-db-스키마-변경-요약)
14. [부록 — 결정 사항 한눈에 보기](#부록--결정-사항-한눈에-보기)

---

## 1. 요약 (Executive Summary)

**목표**: 로컬 단일 사용자 RAG 에이전트를 인증·HTTPS·멀티유저 격리·모바일 대응이 갖춰진 온라인 서비스로 확장한다.

**전제**: SQLite를 가능한 한 유지하고, 명확한 한계 신호가 발생할 때만 PostgreSQL로 전환한다.

| Phase | 핵심 산출물 | 우선순위 | 상태 |
|-------|-----------|---------|------|
| Phase 1 — 보안 기반 | Caddy/HTTPS, Spring Security, 멀티유저 격리, Flyway | **필수** | ✅ 완료 |
| Phase 2 — 모바일 UI | Offcanvas, 하단 고정 입력, PWA | **필수** | ✅ 완료 |
| Phase 3 — 운영 견고화 | Rate limit, 업로드 검증, 감사 로그 | 중요 | 🟡 일부 완료 |
| Phase 4 — 확장 | OAuth2, PostgreSQL 마이그레이션 | 조건부 | 🔵 미착수 |
| Phase 5 — Vector Store 선택 | sqlite-vec / ChromaDB 런타임 선택 | 중요 | ✅ 완료 (Step 5.1~5.10) |
| Phase 6 — 폐쇄망 / 노-도커 | sqlite-vec 단독·로컬 LLM·CDN 0 (키리스 LOCAL, 차원 외부화) | 중요 | 🟢 G1~G5 완료 |
| Phase 7 — 검색 품질·성능 고도화 | 가중 RRF·쿼리 임베딩 캐시(7-A) · Contextual Retrieval(7-B) · 한국어 FTS(7-C) · 정확도 마무리·속도·메모리(7-E) · 큐레이션 Q&A·지식 제안·질의 독립화·청크 신고(§10.10~10.14) | 중요 | ✅ 완료 (7-D 만 범위 제외, §10.5) |

---

## 2. 현재 구조 분석

**강점** — JdbcTemplate 직접 사용(Postgres 전환 시 ANSI SQL만 유지하면 매끄러움) · Immutable Record 상태(`AgentState`/`ChatResponse` 등, 동시성 안전) · Java 21 Virtual Threads · HTMX 서버 렌더링(모바일/PWA 대응 단순) · `VectorStoreRegistry`(컬렉션 키 추상화, 멀티테넌시 전환에 자연스럽게 맞물림).

**Phase 1 착수 시점의 약점** — Phase 1~3에서 전부 해소됨: 인증 없음(→Spring Security 폼 로그인 + no-auth 병행, Step 1.3), `threadId` 소유 개념 없음(→Repository에 userId 강제, Step 1.4), 파일 저장 격리 없음(→이후 공유 저장소로 재단순화, `DocRegistry.SHARED`), HTTPS 미적용(→Caddy, Step 1.1), 마이그레이션 도구 없음(→Flyway, Step 1.2), 모바일 UI 없음(→Offcanvas/PWA, Phase 2), Rate limit 없음(→Bucket4j, §6.1).

---

## 3. 핵심 기술 의사결정

**3.1 인증 — 세션 vs JWT**: `HttpSession + Spring Security` 폼 로그인 채택 — Spring Security 기본 동작과 정합되고 로그아웃·CSRF 통합이 자연스러움. JWT는 다중 서버·네이티브 앱이 필요해질 때 보류(토큰 폐기/갱신을 직접 구현해야 하고 HTMX엔 이득이 적음). Redis 세션 저장소는 다중 인스턴스 필요 시 후속 도입.

**3.2 TLS 종료 위치**: Caddy 리버스 프록시 채택 — Let's Encrypt 자동 갱신 + 설정 최소(Spring 직접 TLS는 인증서 수동 갱신 부담, Cloudflare는 외부 종속). Spring 측은 `server.forward-headers-strategy=framework` + 쿠키 `Secure`+`SameSite=Lax` 강제.

**3.3 SQLite 지속 전략**: 읽기 우세 워크로드라 SQLite 유지, 모든 SQL을 ANSI 표준 + Flyway로 작성해 Postgres 전환을 대비만 해둔다(JdbcTemplate 그대로 사용 가능). **전환 트리거**: `SQLITE_BUSY` 빈도 1%/분 초과, 다중 인스턴스 배포 필요, 응답 지연을 풀 분리로도 해결 불가, 실시간 백업·복제가 요구사항이 됨.

**3.4 멀티테넌시 모델**: Row-level(`user_id` 컬럼) + 사용자별 Chroma 컬렉션 채택(Schema/DB per tenant는 B2B 전용이라 과함) — SQLite 한 파일로 운영 가능. Repository 시그니처를 `getHistory(userId, threadId)`로 강제해 **userId 누락 시 컴파일 에러**가 나도록 안전장치를 걸었다.

> 각 결정의 대안·트레이드오프 비교표는 [부록 — 결정 사항 한눈에 보기](#부록--결정-사항-한눈에-보기) 참고.

---

## 4. Phase 1 — 보안 기반 구축 ✅ 완료

> 상세 구현은 코드 / `db/migration` 참조. 아래는 단계 요약.

### Step 1.1 — Caddy 리버스 프록시 도입 ✅ 완료

Caddy(자동 TLS·HTTP/2)로 `app:8080` 프록시 + 보안 헤더(HSTS 등). Spring 측 `forward-headers-strategy=framework`, 세션 쿠키 `Secure`/`HttpOnly`/`SameSite=Lax`.

### Step 1.2 — Flyway 마이그레이션 도입 ✅ 완료

기존 스키마를 `V1__baseline.sql`로 캡처, `flyway-database-sqlite` 모듈 사용. SQLite 트랜잭션 DDL 제약으로 **마이그레이션 1개당 1 DDL** 원칙.

### Step 1.3 — Spring Security 도입 ✅ 완료

폼 로그인 + `BCryptPasswordEncoder(12)`(~200ms/해시) + CSP/세션 관리. `users`·`persistent_logins` 테이블(`V2`), JdbcTemplate 기반 `SqliteUserDetailsService`. `failed_count` UPDATE는 SQLite write 락 회피로 비동기 처리.

### Step 1.4 — 멀티유저 데이터 격리 ✅ 완료

주요 테이블에 `user_id` 컬럼 + 복합 인덱스(`V3`). Repository 시그니처를 `(userId, …)`로 강제(누락 시 컴파일 에러), 파일 경로·Chroma 컬렉션 사용자별 네이밍. ※ 이후 Phase 3에서 공유 저장소(`DocRegistry.SHARED`) 구조로 단순화됨.

### Step 1.5 — CSRF + HTMX 통합 ✅ 완료

`base.html`에 CSRF 메타 + `htmx:configRequest` 글로벌 헤더 주입, `chat-stream.js` fetch에도 동일 헤더 적용.

### Step 1.6 — 회원가입/로그인 화면 ✅ 완료

`/login`·`/signup` Thymeleaf 페이지, 비밀번호 정책(10자+영문/숫자/특수 각 1), 가입 직후 자동 로그인, 로그인 5회 실패 시 15분 잠금.

---

## 5. Phase 2 — 모바일 UI 개선 ✅ 완료

> 상세 UI 구조는 [UI.md](UI.md) §9(모바일/PWA/접근성) 참조. 아래는 단계 요약.

### 5.1 반응형 레이아웃

`chat.html` 사이드바를 `offcanvas-md`(≥md 고정 컬럼 / <md 햄버거 드로어 `#threadDrawer`)로 전환. 외곽 `.chat-shell`(`100dvh`) + `#chat-messages` flex(`min-height:0`)로 입력창 하단 고정. `documents.html` 테이블 `.table-responsive`(가로 스크롤 제거), `llm-usage.html` 차트 고정 높이 컨테이너 + `maintainAspectRatio:false`. 모바일 폼 컨트롤 `font-size:16px`(iOS 자동 확대 방지).

> ⚠️ Bootstrap `.offcanvas-md`는 ≥md에서 `background-color:transparent!important`를 강제하므로 데스크톱 사이드바 배경을 `var(--bg-elevated)`로 복구해야 한다(`app.css`).

### 5.2 PWA

`manifest.webmanifest`·`sw.js`(NETWORK-FIRST, GET 내비게이션만 가로채 오프라인 fallback — RAG/HTMX/SSE/인증 응답 비캐시)·`offline.html`·`icons/icon.svg`. `base.html`에 manifest/theme-color/apple meta + SW 등록 + iOS 설치 힌트(1회). `WebConfig` MIME 매핑, `SecurityConfig` permitAll에 PWA 경로 추가.

### 5.3 다크모드 & 접근성

다크모드 `prefers-color-scheme` 자동 감지(기존 유지). 아이콘 버튼 `aria-label`(i18n), 모바일 44px 터치 영역, `:focus-visible` 인디케이터.

---

## 6. Phase 3 — 운영 견고화 🟡 일부 완료

> **재배열**: 완료 항목을 앞쪽에(기반 운영 항목 → LLM 사용량 클러스터 → 대화/컨텍스트 클러스터), 미착수 항목을 뒤쪽에 우선순위 순으로 재배치했다. 번호가 이 문서 다른 곳(§7·§12·§13)에서도 참조되므로 교차 참조는 전부 새 번호로 갱신됨.

### 6.1 Rate Limiting — Bucket4j ✅ 완료

`RateLimitFilter`(`OncePerRequestFilter`, `SecurityFilterChain` 앞단 등록)가 엔드포인트별 인메모리 버킷을 적용 — 인증 시 userId, 미인증 시 IP(`ClientIpResolver`) 기준. **현행 기본값**(2026-09-01 실측, `application.properties` 기준): 채팅 60/분, 업로드 10/분, 동기화 3/분, 이미지 서빙 300/분, 그 외 120/분. 정확한 값과 환경변수는 OPERATOR_MANUAL §"Rate Limiting"이 단일 출처다 — 여기 숫자를 복제해 두면 드리프트만 생긴다.

**업로드 버킷은 쓰기 요청만 센다**(2026-09-01 수정): 예전에는 경로에 `/documents` 가 들어가면 전부 업로드 버킷이라 `/documents` 페이지 로드·`/ui/documents/list` 갱신·내보내기·태그 편집이 같은 10/분을 갉아먹었고, 파일 10개를 한 번에 올리는 정상 사용이 마지막 파일에서 429 로 죽었다(페이지 진입 1 + 파일 10 = 11). 지금은 메서드가 쓰기이고 경로가 실제 업로드 엔드포인트일 때만 그 버킷을 쓴다.

**🔵 미착수 — 로그인 전용 버킷**: 이 문서는 오랫동안 "로그인 분당 10회/IP"를 적어 뒀지만 `policyFor()` 에 login 분기는 **존재한 적이 없다** — `/login` 은 `default`(120/분)로 흐른다. 계정 잠금(5회/15분, `AuthEventListener`)이 있으나 그건 **계정 단위**라 아이디를 바꿔 가며 시도하는 패턴은 걸리지 않는다. 노출면이 `app.auth.enabled=true` 배포에만 있으므로 **로그인 기능을 실제로 여는 시점에 §6.19(보안 하드닝)와 함께** 넣는다. 지금 문서는 실제 동작에 맞춰 두었다.

다중 인스턴스 확장 시 Redis 백엔드로 전환 필요(부록 참조).

### 6.2 파일 업로드 보안 강화 ✅ 완료 (리팩토링 03, 12 · 누적 용량 쿼터는 §6.15 로 이관해 완료)

**✅ 완료**
- 확장자 화이트리스트: `pdf, pptx, docx, txt, md`
- **매직바이트 검증** — `security/FileTypeDetector.matches(path, ext)`(Tika 아님, pom에 Tika 의존성 없음). 임시파일 기록 후 검증, 불일치 시 422
- 파일명 sanitize — `Path.normalize()` + 화이트리스트 정규식
- 경로 이탈 방지 — 공유 저장소 `data/documents/`(per-user 격리 폐기, `DocRegistry.SHARED`) 기준 `startsWith()` 검증

**✅ 완료(이관분)** — 누적 용량 쿼터는 §6.15에서 **전역 저장 상한**으로 재설계돼 완료됐다(사용자별 축이 아닌 이유는 그쪽 참조).

### 6.3 글로벌 예외 처리 ✅ 완료

`@RestControllerAdvice`(`GlobalExceptionHandler`) 기반 RFC 9457 ProblemDetail 응답 — `RagException` 서브클래스는 자체 `httpStatus()`로, `MaxUploadSizeExceededException`(413)·`IllegalArgumentException`(400)·미처리 예외(500, `RAG-INT-001`)는 개별 핸들러로 매핑. HTMX 요청엔 `HX-Reswap: none` 헤더 추가.

### 6.4 감사 로그 ✅ 완료 (Logback 파일 롤링)

SQLite `audit_log` 테이블 대신 Logback `SizeAndTimeBasedRollingPolicy`로 구현.
- `data/audit/audit.log` — NDJSON 포맷 (jq 분석 가능)
- 일별 로테이션 + 10MB 분할, gzip 압축, 7일 자동 삭제, 100MB 전체 상한
- `application.properties`로 모든 파라미터 조정 가능, `app.audit.enabled=false`로 즉시 비활성
- 완료 당시 이벤트 8개(upload×2, delete×2, sync×2, routing-mode, thread-delete)로 시작해 이후 기능마다 늘었다 — 현재 목록의 단일 출처는 OPERATOR_MANUAL §3.3 「감사 로그」 표

### 6.5 LLM 사용량 — 임베딩 사용량 분리 ✅ 완료

`TrackingEmbeddingModel`이 `EmbeddingModel`을 데코레이트해 `embed:<model>` 이름으로 채팅과 분리 기록, `/llm-usage` 카드·표·차트 3경로 모두 `type=EMBEDDING`으로 표시. 부수로 `/api/llm/usage` 경로 오타(항상 404) 발견·수정. 이후(2026-09-30) 기록 이름을 모델명 없는 `embed` 하나로 바꿨다 — 임베딩 모델은 배포마다 고정(바꾸면 전체 재인덱싱)이라 모델별 구분은 모델 변경 뒤 옛 모델의 ORPHAN 카드만 남겼다. 기존 `embed:<model>` 행은 Flyway V5 가 날짜별로 합쳐 옮긴다.

### 6.6 LLM 사용량 — 비활성 프로바이더 조건부 표시 ✅ 완료

`LlmUsageRepository.usedProviders()` + `OperationsController.visibleChatProviders()` 공통 필터(`configured || 사용이력있음`)로 카드·표·차트 3경로를 통일 — 키 없는 프로바이더는 이력 없으면 숨기고, 활성 프로바이더는 항상 표시.

### 6.7 LLM 사용량 — 설정에 없는(orphan) 프로바이더 기록 삭제 ✅ 완료

설정에서 제거된 프로바이더의 과거 사용 기록을 `type=ORPHAN`으로 노출하고, `DELETE /admin/llm-usage/{provider:.+}`(관리자 전용, orphan 아니면 400 거부)로 삭제.

### 6.8 Chat 응답 피드백(좋아요/싫어요) 기반 컨텍스트 제외 ✅ 완료

Assistant 응답에 👍/👎 토글 추가(`conversation_turns.feedback`, `PATCH /ui/threads/{threadId}/turns/{turnId}/feedback`). `DISLIKE` turn은 `getHistory()`에서 하드 제외되어 다음 컨텍스트에서 빠진다. `LIKE`는 완료 당시 저장만 했고, 이후 §10.10~10.11 에서 지식 제안 폼을 여는 신호로 소비된다(한때 Direct 턴의 재사용 자격으로도 읽었으나 근거 청크가 없어 검증을 통과한 적이 없는 규칙이라 2026-09-21 제거 — PITFALLS `QuestionReuseRepository.java`).

### 6.9 입력 시작 시 로컬 요약 선계산 + 중복 제거 컨텍스트 압축 ✅ 완료

입력 시작 시(첫 글자) `ConversationSummarizerService`가 이전 대화를 LOCAL 프로바이더로 미리 요약해 스레드별 LRU 캐시(최대 3개, TTL 15초)에 저장 — 캐시 있으면 "요약+최근 2턴", 없으면 기존 `getHistory()`로 조용히 폴백.

**이후 개선 — 답변의 `## 요약` 재사용 + LLM 요약 게이팅**: RAG 답변은 이미 `## 요약` 섹션을 갖고 있으므로 그대로 쓰고 LLM 재요약을 생략한다(`CuratedTextUtils.extractSummarySection()`). 전부 있으면 **LLM 호출 0회**, 일부에 없으면(Direct/meta 답변) 축약 입력에 대해 1회 호출하되 **MICRO_TEXT 전담 소형 모델이 등록됐을 때만** — 부가 기능인 대화 요약이 답변 생성용 `priority=1` 로컬 모델의 슬롯을 잠식하지 않게 하려는 것. 소형 미설정 시(기본값이 없어 오히려 흔한 경로)에도 요약을 포기하지 않고, 요약 섹션이 없는 답변만 `UNSUMMARIZED_ANSWER_CAP`(300자)으로 자른다 — `truncate()`가 **앞에서부터** 자르므로 앞쪽의 긴 Direct 답변 하나가 예산을 독식하면 최신 턴이 통째로 밀려나기 때문.

### 6.10 LLM 사용량 — 백그라운드(비-채팅) 사용량 분리 기록 ✅ 완료

채팅 외 LLM 호출(요약·키워드추출·서식교정·TXT→MD·제목생성)을 `BackgroundUsage` 접두사(`summary:`/`keyword:`/`mdcorrect:`/`txt2md:`/`title:`)로 채팅과 분리 기록, `/llm-usage`에 `type=BACKGROUND` 카드로 노출. 조사 중 발견한 핵심 채팅 경로 자체의 추적 공백은 §6.14로 분리했다.

---

### 6.11 대화 컨텍스트 예산 정합성 + 설정 외부화 ✅ 완료

§6.9 도입 후 요약 경로에는 없던 문자 예산 체크를 `MemoryService.maxConversationChars()`(단일 진실 원천, `max(1000, LLM_MAX_TOKENS × 0.5)` — 도입 당시 0.75였으나 요약 입력 상한을 조이면서 0.5로 낮췄다)로 통일하고, `FETCH_LIMIT` 등 하드코딩 5개 값을 `app.memory.*`/`app.summary.*` 프로퍼티로 외부화.

---

### 6.12 다중 사용자 동시 LLM 요청 처리 — 동시성 제어 + 처리량 확장 ✅ 완료

채팅 경로에 동시성 제한이 전혀 없어(세마포어는 인덱싱 경로에만 존재) 여러 사용자가 겹치면 폴백 없는 유일 프로바이더가 서킷브레이커로 전면 차단되던 문제를 5단계로 해결: ① 프로바이더별 동시성 세마포어(`LlmRouter.acquirePermit`/`executeGated`, `app.llm.providers[].concurrency`) ② 대기상한 초과 시 429 백프레셔(`LlmBackpressureException`, 서킷브레이커 없음) ③ `CachingEmbeddingModel` in-flight single-flight(동일 텍스트 중복요청 병합) ④ 폴백 없는 유일 프로바이더의 서킷브레이커 단축 차단(30초) ⑤ 동일 role·priority 프로바이더 간 least-in-flight 로드밸런싱. 인덱싱/백그라운드 경로는 기존 세마포어를 유지하며 미적용. **⑥ 장애 전환 범위 확장(2026-10-08)** — 같은 로컬 서버 두 대(`LOCAL_LLM_URL`·`LOCAL_LLM_URL_2`) 중 먼저 등록된 쪽이 죽어도 채팅만 다른 서버로 넘어가지 못하던 문제: 연결 타임아웃은 이제 차단 + 같은 호출 안에서 전환(읽기 타임아웃은 그대로), 채팅 답변 스트리밍은 첫 토큰 전 서버 장애에 한해 `StreamFailover`/`LlmRouter.failOver()` 로 다음 서버에 다시 연다, 질의 확장은 `RoutedChatModel` 로 호출마다 라우팅한다. 남는 한계와 근거는 PITFALLS `llm/StreamFailover.java`. **⑦ 이중화 마무리(2026-10-12)** — ⑥ 의 남은 한계 셋 중 둘을 닫았다: 임베딩은 `LoadBalancingEmbeddingModel` 이 자기 목록(`EMBED_BASE_URL` + `EMBED_ADDITIONAL_BASE_URLS`) 안에서 **닿지 않는 엔드포인트만** 30초 건너뛰고(5xx·읽기 타임아웃은 요청 모양 문제라 넘기지 않는다, 이중화인데 임베딩이 한 대에만 걸려 있으면 기동 시 `[EMBED CONFIG]` 경고), 기동 검증(G3)은 같은 type·priority 의 복제본이 검증을 통과하면 닿지 않거나 응답하지 못하는(5xx) 서버를 **30초 차단된 채** 등록하고 기동한다(설정 오류 — 4xx·모델 목록이 아닌 응답·모델 없음·모호함 — 는 여전히 실패). 남은 것은 멈춘 서버(읽기 타임아웃) 하나다.

---

### 6.13 설정 페이지 — LLM/RAG 옵션 조회·부분 수정 ✅ 완료

`GET /settings`(게스트 조회 가능, 편집은 `isAdmin` 게이트)에서 LLM/임베딩/RAG 설정을 조회하고, `settings_override` 테이블 기반 오버레이 레이어로 **재기동 없이** 반영한다 — 검색 값은 다음 검색부터, 인덱싱/청킹 값은 다음 인덱싱/↺ 재인덱싱부터. 핵심 규칙은 **소비처가 값을 필드에 캐시하지 않고 매 호출 `props.xxxSafe()`로 재조회**하는 것(`MarkdownCorrectionService`의 생성자 캐시를 제거해 세 소비처의 동작을 일치시켰다). 빈 생성 시점에 고정되는 값(rerank-enabled 등)과 기본 라우팅 모드는 조회 전용. 수정은 `/admin/settings/update|reset`(§6.17 ROLE_ADMIN 상속) + 감사 로그. **핫 키 추가 절차와 전체 키 목록은 CLAUDE.md §6.13 항목이 단일 출처.**

---

### 6.14 LLM 사용량 — 핵심 채팅 경로 추적 확장 ✅ 완료

`AnswerService`/`DirectAnswerService`/`ClassifierService`/`RerankerService`/`VisionDescriptionService`/`ImageTypeClassifier`/`RetrievalService`의 `MultiQueryExpander` 7곳이 `LlmRouter`를 거치지 않아 실제 채팅 사용량이 `/llm-usage`에 전혀 안 잡히던 문제(사용자 실사용 중 보고)를 발견·수정 — 블로킹 호출은 `executeWithTracking()`으로, 스트리밍은 `LlmRouter.recordApproxUsage()`로, 프레임워크가 내부에서 자체 `ChatClient`를 구성해 가로챌 수 없는 `MultiQueryExpander`는 신규 `TrackingChatModel` 데코레이터로 해결(2026-10-08 이후에는 호출마다 라우터를 지나는 `RoutedChatModel` 로 대체되어 그 데코레이터와 `ConcurrencyLimitingChatModel` 은 삭제 — §6.12 ⑥).

---

### 6.15 스토리지 쿼터 — 전역 저장 상한 ✅ 완료 2026-09-01 (§6.2에서 이관, B안)

**유일한 설계 결정은 축을 (A) 사용자별에서 (B) 전역으로 바꾼 것이다** — 저장소가 공유 구조(`DocRegistry.SHARED`, 단일 `data/documents/`)이고 기본 배포가 no-auth 단일 운영자라 "이 사용자가 N바이트를 썼다"를 붙일 데가 없다. `app.upload.max-total-size`(`DataSize`, `0` = 무제한이 기본)를 문서 업로드 2곳과 `CuratedImageStore.store()` 가 쓰기 **전에** `StorageQuotaService.checkCanAccept()` 로 확인하고, 넘으면 413 + `RAG-UP-002`(`Retry-After` 없음 — 기다린다고 자리가 생기지 않는다). 측정은 `documents/`·`converted/`·`images/` 세 트리의 디스크 walk(소프트 캡)이고, 삭제한 문서의 원본이 옮겨지는 `documents/backup/` 은 측정에서 빼는 대신 `DocumentBackupCleaner` 가 묶는다(최신본 1개 → 30일 → 2GB). `/settings` 에는 사용량·상한 2행(조회 전용). (A)는 멀티테넌트 과금이 필요해질 때의 후속이다.

결정·함정(세 트리, 소프트 캡, `backup/` 제외와 정리기의 짝, 진입점마다 검사, 화면 쪽만 30초 캐시)은 PITFALLS [`StorageQuotaService`](PITFALLS.md#servicestoragequotaservicejava)·[`DocumentBackupCleaner`](PITFALLS.md#servicedocumentbackupcleanerjava). 압축 전 전문은 커밋 `c9a9ec4` 의 PLAN.md.

**남은 열린 항목**: 업로드 검사 경로에는 캐시가 없다(다중 업로드가 낡은 값 하나로 연달아 통과하지 않게 — 이미지 수십만 개 규모에서 느려지면 다시 본다) · 백업 정리는 삭제 직후와 기동 시에만 돈다(오래 지우지 않는 배포는 30일 규칙이 늦게 반영된다 — 필요해지면 스케줄러) · 상한 근접 사전 경고가 없다(거부가 첫 신호다 — `/documents` 사용률 배지는 별도 UI 작업).

---

### 6.16 사용자 경험(UX) 개선 🟡 일부 완료 (6.16.1)

**6.16.1 스트리밍 답변·인덱싱 중단(취소) 컨트롤 부재 ✅ 완료**

채팅은 `chat-stream.js`의 `AbortController`로 중지(전송 버튼→중지 버튼 전환, 중단 시점까지 답변 보존). 인덱싱은 신규 `IndexingCancelledException` + `POST /ui/documents/progress/{taskId}/cancel`(워커 스레드 `interrupt()`)로 취소 — `KeywordExtractor.enrichParallel()`/`DocumentIndexer.syncDirectory()`가 non-interruptible `CompletableFuture.join()`을 쓰던 버그를 `.get()`으로 교체해 실제 취소되도록 수정. 취소 시점까지 완료된 파일은 레지스트리에 보존.

**6.16.2 계정 잠금 상태 피드백 부재**
- **현재 상태**: 로그인 5회 실패 시 15분 잠금(`AuthEventListener`)되지만, 잠긴 뒤에도 로그인 화면은 일반 "이메일 또는 비밀번호가 올바르지 않습니다" 문구만 보여준다(`formLogin.failureUrl("/login?error")` 고정). 사용자는 자신이 잠겼는지, 언제 풀리는지 알 수 없다(문구가 "5회 실패 시 15분 잠금"을 안내는 하지만 현재 잠금 상태/해제 시각은 아님).
- **개선안**: `LockedException` 분기를 별도 실패 핸들러로 잡아 "N분 후 해제" 메시지 전달(`locked_until` 조회). 잠금 남은 시간은 이미 `SqliteUserDetailsService`가 관리 중.
- **완료 기준**: 잠긴 계정 로그인 시 잠금 상태 + 해제 예정 시각이 구분되어 표시된다.

---

### 6.17 문서 관리·Admin 접근 인증 필수화 + 역할 기반 화면 분기 ✅ 완료 (B안)

no-auth 기본 배포에서 `/documents` 쓰기와 `/admin/**`이 로그인 없이 열려 있던 문제를, 채팅·조회는 no-auth로 두고 `/documents`(쓰기)·`/admin/**`만 로그인 요구하는 (B) 관리 전용 인증으로 해결 — `app.auth.management-only=true` 신규 서브모드, `SecurityConfig` 3번째 필터 체인(`IF_REQUIRED`+쿠키 CSRF), `hasRole("ADMIN")` 게이트, `GlobalModelAdvice.isAdmin`으로 화면 분기. (A) 전체 인증 모드는 §6.19와 함께 후속(멀티유저 활성화 시).

---

### 6.18 Direct 메시지 전용 LLM Temperature 분리 ✅ 완료

라우터 경로가 Spring AI 오토컨피규레이션을 우회해 `LLM_TEMPERATURE` 등 기존 환경변수가 **전부 죽은 설정**이던 문제 — 하드코딩 4곳을 제거하고 `app.llm.temperature`(일반/RAG)·`app.llm.direct-temperature`(Direct 전용)·`app.llm.max-tokens`로 전환. direct-temperature만 매 호출 재조회해 핫 수정(블로킹은 `Prompt`, 스트리밍은 `ChatCompletionRequest`에 주입). 이후 §6.13 확장으로 세 temperature가 모두 핫이 됐고(§6.24 에서 creative-temperature 가 넷째로 추가), max-tokens 는 §6.26 A6 에서 마지막으로 핫 편집 대상이 됐다(현행 clamp·소비처는 CLAUDE.md §6.13 항목 참조).

**동작 변경(주의)**: `MemoryService`·`MarkdownCorrectionService`가 읽던 죽은 `spring.ai.openai.chat.options.max-tokens`(기본 8000)를 `props.llmSafe().maxTokens()`(6000)로 통일하면서 **대화 히스토리 예산 6000→4500자, MD 교정 섹션 크기 3750→2750자**로 기본값이 줄었다. 과거 분량을 유지하려면 `LLM_MAX_TOKENS`를 올려야 한다.

---

### 6.19 보안 하드닝 🟡 §6.19.2·6.19.3·6.19.4 완료 · §6.19.1만 남음

> **범위 판단의 교훈**: 처음엔 세 건 모두 `app.auth.enabled=true`(멀티유저 인증) 전용으로 보고 "인증 켜고 다중 사용자로 열 때 반드시 선행"하는 조건부 항목으로 뒀다. 그 전제가 절반만 맞았다 — §6.19.3(XFF)은 §6.22 에서 IP 가 식별자가 되면서 no-auth 배포의 선행 필수가 됐고, 2026-09-07 실사에서는 **no-auth·management-only 조합에서만 성립하는** 노출면이 여럿 나왔다(§6.19.4). **"어느 모드에서 성립하는가"를 항목마다 다시 묻는 편이 낫다.**

**6.19.1 `/api/v1/**` CSRF 비활성 + 세션 인증 혼용 (CSRF 노출)** 🟡 **노출면 축소, 원 항목은 미해결**
- **현재 코드**: `SecurityConfig`가 인증 모드에서 `csrf.ignoringRequestMatchers("/api/v1/**")`로 API 전체를 CSRF 예외 처리하지만, 같은 엔드포인트가 **세션 쿠키 인증**이다. `POST /api/v1/documents`는 `multipart/form-data`(CSRF "simple request")라 크로스사이트 폼으로 인증된 사용자의 브라우저에서 업로드를 유발할 수 있다(`DELETE`·JSON body 경로는 preflight로 상대적으로 안전).
- **개선안**: (A) API를 브라우저 세션이 아닌 **별도 인증(API 토큰/헤더)** 전용으로 못박고 CSRF 예외를 유지하거나, (B) 세션 인증을 계속 쓸 거면 `/api/v1/**`도 CSRF 토큰을 요구(현 HTMX/폼 경로 `/ui/**`는 이미 CSRF 적용). 최소 조치로 `POST /api/v1/documents`만이라도 CSRF 토큰 또는 커스텀 헤더 요구.
- **완료 기준**: 인증 모드에서 크로스사이트 폼 제출로 인증 사용자의 업로드/삭제가 트리거되지 않음(테스트로 재현→차단 확인). no-auth 모드 회귀 0.
- **2026-09-07 진행분(부분)**: 두 가지가 노출면을 줄였지만 **위 완료 기준은 아직 충족하지 않는다**. ① `/api/**`의 CORS 와일드카드 제거 — 크로스 오리진에서 **응답을 읽는** 경로와 프리플라이트가 필요한 `DELETE`는 막혔다. 그러나 `multipart/form-data` POST는 CORS 없이도 전송되는 "simple request"라 이 항목의 핵심 시나리오는 그대로다. ② 문서 쓰기 3종을 `ROLE_ADMIN`으로 게이트(§6.19.2 처리분과 같은 목록) — 피해자가 **관리자일 때만** 성립하도록 좁혔을 뿐, 관리자의 브라우저는 여전히 CSRF 대상이다. 남은 일은 (A)/(B) 중 택일이며, 지금 상태에서는 (B)가 자연스럽다 — 자동화가 이미 로그인 세션을 쓰므로 CSRF 토큰을 요구해도 잃는 것이 적다.

**6.19.2 `/admin/**` 인가 공백** ✅ **완료 2026-09-06** — 인증 모드에서 ROLE_ADMIN 을 요구하던 경로가 `DELETE /admin/llm-usage/**` 하나뿐이라, 로그인한 일반 사용자가 청크 조회·편집·삭제·재인덱싱은 물론 착수 사이 `/admin` 아래로 들어온 전 사용자 대화 열람·삭제, 런타임 설정, 지식 제안 승인(§10.11 검색 코퍼스의 유일한 문)까지 할 수 있었다. `/admin/**` 전체와 `/actuator/**`(health 제외 — `POST /actuator/loggers` 로 TRACE 를 켜면 `LlmCurlLogger` 가 검색 문서가 실린 프롬프트 전문을 남긴다)를 두 모드 모두 `hasRole("ADMIN")` 으로 묶었다(`FullAuthAuthorizationTest`·`ManagementOnlyAuthorizationTest`).

**6.19.3 Rate limit — `X-Forwarded-For` 무검증 신뢰** ✅ **완료** (§6.22와 함께 선행 처리) — XFF 판정을 `ClientIpResolver` 하나로 모으고 **최상위** `app.trust-forwarded-for`(기본 `false`) 옵트인일 때만 신뢰한다(최상위인 이유는 §6.22 방문자 식별이 같은 판정을 쓰기 때문). 원래 멀티유저 후속이었으나 §6.22 에서 IP 가 **식별자**가 되는 순간 XFF 위조가 남의 대화 목록 열람이 되어 no-auth 배포의 선행 필수가 됐다. 2026-09-07 에 셋째 소비자 `AuditLogger` 가 이 플래그를 보지 않고 헤더를 무조건 믿던 것을 고쳤다 — **판정을 한 곳에 모으는 것만으로는 부족하고, 새 소비자가 그것을 실제로 부르는지까지 봐야 한다.** ⚠️ 프록시 뒤 배포는 `TRUST_FORWARDED_FOR=true` 필수(끄면 전 방문자가 프록시 주소 하나로 합쳐진다).

**6.19.4 폐쇄망 배포 실사에서 나온 하드닝** ✅ **완료 2026-09-07** — 실제 운영 설정(`AUTH_ENABLED=false`·`AUTH_MANAGEMENT_ONLY=true`·`AUTH_GUEST_IDENTITY=hybrid`·`TRUST_FORWARDED_FOR=false`) 기준으로 **"지금 걸리는가"와 "설정을 바꾸면 걸리는가"를 나눠 둘 다 고쳤다**: `/api/**` CORS 와일드카드 제거(잃는 기능 없음) · management-only 의 REST 문서 쓰기(`POST /api/v1/documents`·`/sync`·`DELETE`) 게이트 · full-auth 의 문서 관리 RBAC(두 모드가 `gateDocumentManagement()` 하나를 공유) + 그 짝으로 `/setup` 을 모드 무관하게 개방 · 공유 게스트(`guest-identity=shared`)의 레이트리밋 키를 IP 로 · 게스트 개방 이미지 업로드를 `upload` 버킷으로 + 고아 정리를 6시간 주기로 · 감사 로그 IP·`X-Trace-Id` 모양 검사·REST 호출의 세션 생성 중단 · 컨테이너 비-root(UID 10001 — 기존 Linux 배포는 `chown -R 10001:10001 ./data` 1회, 엔트리포인트가 안내한다).

**유지해야 할 결정**: 문서 관리 게이트 목록은 두 인증 모드가 공유하는 하나다(한쪽에만 추가하면 같은 구멍이 다시 난다) · REST 쓰기 3종은 `NoAuthAutoLoginFilter.isGatedManagementPath()` 에 일부러 넣지 않았다(302 로그인 페이지가 아니라 API 호출자에게 맞는 403) · `UPLOAD_MAX_TOTAL_SIZE` 의 기본 무제한은 그대로다(바꾸면 기존 배포가 조용히 업로드를 거부한다 — 게스트 개방 배포는 운영자가 설정한다). 동작 명세는 CLAUDE.md `SecurityConfig`·`RateLimitFilter` 항목. 압축 전 전문은 커밋 `c9a9ec4` 의 PLAN.md.

---

### 6.22 접속자별 채팅 개인화 (no-auth) ✅ 완료

no-auth 배포에서 모든 방문자가 고정 게스트 id 하나를 공유해 스레드 목록·대화 이력이 섞이던 문제를, `NoAuthAutoLoginFilter`가 주입하는 게스트 principal의 id **한 곳만** 방문자별로 파생해 해결(저장 계층은 Step 1.4에서 이미 `user_id` 축으로 격리돼 있었고 상수를 먹고 있었을 뿐 → 저장·서비스 계층 변경 0). `app.auth.guest-identity` = `shared`(기본, 회귀 0)/`ip`/`cookie`/`hybrid`(권장), id는 `guest-<12 hex>` = HMAC-SHA256(`app_secret` 테이블의 영속 시크릿, 방문자 키). 메커니즘 상세는 CLAUDE.md `GuestIdentityResolver` 항목.

**유지해야 할 사실**
- 인증을 켜면 `GuestIdentityResolver`가 `@ConditionalOnProperty(app.auth.enabled=false)`로 컨텍스트에서 사라진다 — 파생 게스트 id와 실제 로그인 id가 동시에 살아 있는 경로가 없다. 전환 시 기존 게스트 스레드는 `guest-%` 접두사로 일괄 삭제하거나 실계정에 귀속 가능
- 문서 저장은 공유 유지(`DocRegistry.SHARED`) — 개인화 대상은 채팅 스레드·이력·좋아요 소유권뿐
- 부수 효과: §6.20 (A)안(`conversation_turns` 기반 집계)이 no-auth에서도 의미를 갖게 됐고, 큐레이션 "본인만 편집"(`source_user_id`)이 비로소 실동작
- ⚠️ 이 설정을 켜기 전 쌓인 스레드는 옛 공용 id에 묶여 조회되지 않는다(삭제는 아니며 `shared`로 되돌리면 복귀). 채택 시 운영자 고지 — OPERATOR_MANUAL §9.4.3

---

### 6.23 청크 변경 시 답변 재사용 무효화 + 대화 기록 표시 ✅ 완료

> **결정 근거와 틀렸던 지점** → [PITFALLS.md](PITFALLS.md#623-청크-변경-시-답변-재사용-무효화--대화-기록-표시--완료)

답변 재사용(`/api/v1/questions/reuse`)이 근거 청크가 그 사이 삭제·수정된 답변을 사실처럼 되돌려주던 문제를 **스냅샷 대조**로 해결 — 턴 저장 시 `(chunk_id, sha256(chunk_fts.content), answer_share)`를 `turn_source_ref`에 남기고 재사용 직전 대조하며, 삭제/재인덱싱 경로는 별도로 즉시 무효화 표시를 남겨 이중으로 막는다. 대화 기록에는 `SourceRef.staleBadge()` 한 곳이 정한 규칙으로 **삭제됨/수정됨** 배지를 띄운다. 메커니즘·테이블·배지 규칙 상세는 CLAUDE.md의 `QuestionReuseService`/`QuestionReuseRepository`/`SourceRef` 항목.

---

### 6.24 응답 모드 재설계 — S/N/C (L 제거 · 모드별 전용 시스템 프롬프트) ✅ 완료 2026-08-24 — 설계 확정 2026-08-22 (분량 지침·`/settings` 배선 보강 2026-08-23, 완료 후 보정 2026-08-25)

> **결정 근거와 틀렸던 지점** → [PITFALLS.md](PITFALLS.md#624-응답-모드-재설계--snc-l-제거--모드별-전용-시스템-프롬프트--완료-2026-08-24--설계-확정-2026-08-22-분량-지침settings-배선-보강-2026-08-23-완료-후-보정-2026-08-25)

길이 축(S/M/L)에 "문서를 **재료로** 코드·설정을 생성"이라는 **다른 축**(근거 엄격도)을 얹으려다, 기존 축부터 검토해 **M과 L이 구분되지 않음**을 확인하고 재설계했다. M/L이 같았던 이유는 튜닝이 아니라 구조다 — 채팅의 유일한 전송 경로인 스트리밍에는 `maxTokens`가 붙지 않고, `RetrievalService`가 모드를 몰라 두 모드의 재료(topK)가 동일하며, 남은 차이인 "약 N자" 문구는 모델을 움직이지 못했다(실측 M 3,047자 / L 3,187자, **둘 다 M의 목표 5,000자 미달**). 그래서 L 제거·M→N 개명 후, 모드마다 **시스템 프롬프트를 통째로** 주고(공용 프롬프트를 사용자 메시지로 뒤집던 층은 삭제) 응용 모드 **C**를 신설했다. Phase 0(`0-a`~`0-e`) · 1(`1-a`~`1-d`) · 2(`2-a`~`2-d`) · 3(`3-a`~`3-c`) · 4(`4-a`·`4-b`) 완료, `4-c`만 미착수(아래). 모드별 동작 명세는 CLAUDE.md의 Response mode 항목과 PIPELINE §3.1.

---

### 6.25 관리자 — 전체 대화 목록 조회·삭제 + 검색 진단 연결 ✅ 완료 2026-08-28

관리자가 `/admin` 에서 **전 사용자의 대화 목록**(제목·최종 활동·턴 수·재사용 수·피드백)을 보고, 대화를 삭제하고, 그 대화의 검색 진단 수치로 바로 내려간다 — §7.3(관리자 페이지 확장)의 "대화 관리"를 선반영한 것이고 계정 관리·감사 로그 뷰는 §7.3 에 남아 있다. **새 스키마 0개**: `ThreadAdminRepository`/`ThreadAdminService`(조회·삭제) · `fragments/admin-threads.html` · `fragments/admin-source-table.html`(진단 패널과 공유) · `AdminController` 의 `/admin/threads*`. **Step 1**(선행 버그 픽스): 대화를 통째로 지우면 좋아요로 승격된 `curated_qa` 행과 벡터가 남아 검색에 계속 기여했다(턴 삭제는 막고 있었다) — `CuratedQaService.onThreadDeleted()` 를 사용자·관리자 경로가 함께 부른다.

**소스 주석이 가리키는 결정**: **결정 3** — 답변 원문은 목록과 다른 쿼리(`findTurnContent()`)로만 나가고 내용이 실제로 나갈 때 `admin.thread.read` 감사를 남긴다(목록의 `TurnRow` 에는 `answer` 필드가 아예 없다 — 감추는 것을 템플릿이 아니라 구조로 강제한다. **Step 6** 의 원문 열람). **결정 4** — 대화 삭제는 큐레이션을 먼저 회수한다(수동 제안 행은 `source_turn_id IS NOT NULL` 로 배제 — 제안은 전부/전무 단위). 그 밖의 결정·함정(두 재사용 카운터와 상관 서브쿼리, 삭제는 thread id 만 받고 확인 숫자는 클릭 시점 재조회, 벌크 삭제 없음, `/api/v1` 밖, `ThreadMetaRepository` 와 분리, 진단 필터의 배타 규칙, C 턴의 `해당 없음`, 고아 카운트와 LEFT 조인, 테이블 `min-width`)은 PITFALLS [`ThreadAdminRepository`](PITFALLS.md#repositorythreadadminrepositoryjava--servicethreadadminservicejava)·[대화 삭제](PITFALLS.md#대화). 재현할 때의 환경 함정: `spring-boot:run` 은 `target/classes` 의 템플릿 사본을 캐시하므로 프래그먼트 수정은 `mvn compile` + 재시작이 필요하고, `-DDATA_DIR` 에 git-bash 경로(`/c/Users/...`)를 넘기면 `C:\c\Users\...` 에 빈 DB 가 생긴다. 압축 전 전문은 커밋 `c9a9ec4` 의 PLAN.md.

**남은 열린 항목**: ~~저장 시각의 존 통일~~ — **2026-10-14 해소**: DB 에 쓰는 시각은 전부 UTC(`DbTimestamp`, 옛 값은 Flyway V9 가 이관), 사람이 읽는 서버 시계는 `app.time-zone`, 화면의 시각은 브라우저가 보는 사람의 시간대로 바꾼다(PITFALLS "DB 시각은 UTC 로만 쓴다"·"사람이 읽는 서버 시계는 `app.time-zone` 이다"·"화면의 시각은 브라우저가 바꾼다") · `curated_qa.source_thread_id` 인덱스(대화 목록에 큐레이션 수 열을 넣고 싶어질 때) · 전사 트랜스크립트 검색/뷰어(필요해지면 §7.3) · ~~고아 `turn_source_ref`~~ — **2026-10-12 해소**(Flyway V7 이 한 번 지웠고, 지금의 삭제 경로는 새로 만들지 않는다).

---

### 6.27 검증 실패 재시도 개선 ✅ 완료 2026-09-02

**문제**: 재시도가 사실상 같은 입력의 반복이었다 — 두 게이트(`sufficient=false`/`grounded=false`)가 같은 노드로 갔고, 돌아가서 달라지는 것은 문서 **1개**뿐이었으며(`topK + retryCount`), 온도는 낮게 clamp 돼 같은 입력이면 거의 같은 답이 나왔다. 그런데 반려 사유(`evalReason`)는 이미 계산돼 로그·SSE·DB 로 나가면서 정작 모델에게만 가지 않았다.

**고친 것**(근거는 PITFALLS [`AgentGraph`](PITFALLS.md#agentagentgraphjava)·[`RetrievalEviction`](PITFALLS.md#serviceretrievalevictionjava)·[`RetrievalService`](PITFALLS.md#serviceretrievalservicejava)): 재시도 프롬프트의 `[직전 시도 메모]`(`AnswerService.appendRetryFeedback` — 추가 왕복 0, **지시가 아니라 관찰로** 써야 지적을 채우려고 지어내지 않는다) · **게이트별 분기**(`sufficient=false` → RETRIEVAL, `grounded=false` → ANSWER — 메모가 선행돼야만 성립하는 순서다) · escalation 의 입력을 `retryCount` 가 아니라 `retrievalRetries` 로 · **덧붙이는 대신 밀어낸다**(`RetrievalEviction` — 평가가 근거로 안 썼고 RRF 하위 절반일 때만, 가드 셋: 빈 `usedDocIndices` 는 "모른다", 잘린 발췌의 뒤쪽은 "보이지도 않은 것", 1순위 보존; `grounded=false` 에는 적용 안 함) · 후보 풀 ×2 → ×1.5 · 문서 +1 은 **검증 호출**의 컨텍스트 여유가 있을 때만(넘치면 판정이 사라진다 — 창을 모르면 늘린다). 부수 효과로 §6.24 `4-c`(검색 부스트)의 전제 하나가 사라졌다. 압축 전 전문은 커밋 `c9a9ec4` 의 PLAN.md.

**남은 열린 항목**: ~~조기 종료 미구현~~ — **2026-10-12 해소**: 같은 게이트로 **같은 청크 집합**을 보고 두 번 반려되면 상한이 남아도 멈춘다(`AgentState.repeatsLastRejection`, 로그 `[AgentGraph] retry stopped early`). 계획의 "`evalReason` 비교"는 쓰지 않았다(매번 다르게 쓰는 자유 문장이다). PROGRESSIVE 는 같은 판정을 "소진"으로 읽어 곧바로 업그레이드한다 · 효과 측정은 `[AgentGraph] retry #N ... detail=` 로그로 한다(별도 지표 없음) · `EVAL_OVERHEAD_TOKENS`(1,500)는 측정이 아니라 허용치다 — 과대 추정은 "늘리지 않음"으로 떨어져 안전한 방향이다.

---

### 6.28 로컬 LLM 장애 가시성 — 생사 핑 · 소진 문구 · traceId 전파 ✅ 완료 2026-09-22

> **결정 근거와 틀렸던 지점** → [PITFALLS.md](PITFALLS.md#llmllmpingjava) · [연속 실패와 소진 문구](PITFALLS.md#llmcircuitbreakerjava--연속-실패와-소진-문구) · [MdcPropagation](PITFALLS.md#webmdcpropagationjava)

**문제.** 2026-09-21 로컬 LLM 의 GPU 가 소실됐다(`decode() failed: vk::Queue::submit: ErrorDeviceLost`). 서버 프로세스와 `/v1/models` 는 멀쩡했고 모델은 "로드됨"이었으며 엔진만 죽었는데, 앱은 그것을 알 방법이 없었다 — 사용자는 5초 차단마다 "4초 후 다시 시도"(거짓)를, 그것도 사전 분류 future 의 `CompletionException` 이 전용 catch 를 지나쳐 **클래스 이름이 붙은 raw 문구**로 받았고, 로그는 모든 줄이 `[-]` 라 한 사고를 trace id 로 묶을 수 없었다.

**구현 결과.** `LlmPing`(`GET /api/v1/llm/ping`, 200/503 — 닿는가 / 로드됐는가 / `deep=true` 1토큰 추론이 되는가를 따로 답한다, 라우터·브레이커 우회) · `/api/v1/llm/concurrency` 의 `blockedSeconds` 와 헤더의 `LLM: 차단 중 Ns` · `CircuitBreaker` 의 연속 실패 횟수 + `LlmProviderExhaustedException.consecutiveFailures` + 3회부터 "서버(모델) 상태를 확인하라"(`LlmOutageMessages`, 한/영) · `AsyncExceptions.unwrap` · `MdcPropagation`(채팅 워커·사전 future·검색 축·Vision·인덱싱 워커와 그 안의 중첩 실행기 전부) + `TraceIdFilter` 의 ASYNC 재디스패치.

**앞으로도 지켜야 할 결정**

- **"살아 있는가"는 세 답이다** — `/v1/models`·`/health` 로는 엔진 사망을 구분할 수 없고, 1토큰 추론만이 가른다. 핑은 진단이라 브레이커를 만지지 않는다.
- **소진 문구는 예외 메시지가 아니라 예외가 나르는 사실(`repeated()`·`retryAfterSeconds()`)로 고른다** — 예외 메시지는 한국어 고정에 `(task=TEXT)` 꼬리가 붙는다. 연속 실패 횟수는 성공이 있어야만 0 이다(차단 만료로 지우면 죽은 서버가 매번 첫 실패처럼 보인다).
- **future 로 LLM 을 부르는 자리는 `CompletionException` 을 풀어야 한다** — 안 풀면 예상된 장애가 ERROR + `RAG-INT-001` 이 된다.
- **새로 실행기나 `Thread.ofVirtual()` 을 여는 자리는 `MdcPropagation` 으로 감싼다** — 감싸지 않은 한 곳의 줄만 `[-]` 로 남는다. `SseHeartbeat` 만 예외(스케줄러가 깨우므로 넘겨줄 MDC 가 없다).

**남은 열린 항목**

- 핑은 요청이 있어야 돈다 — 주기적 자가 점검(예: 표시기 폴링에 얕은 핑을 얹기)은 넣지 않았다. 브레이커 상태로 충분한지 운영에서 본 뒤 결정.
- 연속 실패 문턱(3)은 상수다(`LlmProviderExhaustedException.REPEATED_FAILURE_THRESHOLD`). 프로퍼티로 뺄 이유가 생기기 전까지는 그대로.

---

### 6.26 컨텍스트 초과 대응 — 창 인지 + 입력 예산 ✅ 완료 2026-09-01

> **결정 근거와 틀렸던 지점** → [PITFALLS.md](PITFALLS.md#626-컨텍스트-초과-대응--창-인지--입력-예산--완료-2026-09-01)

실배포 로그에서 시작했다: `Context size has been exceeded.` → `CircuitBreaker` 가 유일한 LOCAL 프로바이더를 30초 차단 → 그동안 들어온 **작아서 통과했을 요청까지** `All providers exhausted` 로 함께 실패. 원인은 프롬프트 크기인데 증상은 프로바이더 장애로 나타났고, 사용자에게는 "모든 AI 프로바이더를 사용할 수 없습니다"가 갔다. 아무도 고칠 곳을 찾을 수 없는 조합이다.

**11단계로 나눠 진행했다** — 각 단계가 단독으로 가치가 있고 뒤 단계의 전제가 된다.

1. **차단하지 않기** (`isContextOverflow`) — 결정적 오류라 기다려도 같은 요청은 똑같이 실패한다. `isTimeoutLike`(클라이언트 측)·`isVisionUnsupported`(모델 성질)에 이은 **세 번째** "프로바이더가 아픈 게 아니다" 분류다.
2. **오류 구분** (`LlmContextOverflowException`, `RAG-LLM-003`) — 프롬프트 크기 문제로 안내한다.
3. **프로바이더별 `max-tokens`** — 창 크기가 모델마다 다르니 상한도 하나일 수 없다.
4. **컨텍스트 창 학습** (`context-size` 선언 또는 `ContextWindowProbe` 탐지).
5. **토큰 추정 통일** (`TokenEstimator`) — §6.24 남은 이슈였고, 여기서 선결 조건이 됐다.
6. **입력 예산 사전 축소** (`PromptBudget` + `AnswerService.fitToBudget()`).
7. **축소 사실 노출** (`AgentState.budgetNote` + 출처별 `미사용` 표시).
8. **인덱싱 입력 예산** (`PromptBudget.rewriteInputChars()`) — 정작 사고가 났던 경로. 6번은 답변·검증만 덮었는데 실배포에서 초과가 난 것은 **MD 교정**이었다.
9. **축소 후 재시도** (A4 · 소스의 `§6.26-9`, `AnswerService.withShrinkRetry()`) — 1~8번이 모두 **추정** 위에 서 있다는 사실을 받아내는 마지막 층. 채팅 답변 스트리밍에서는 2026-10-02 에야 실제로 발동하기 시작했다(§6.29 3단계, `LlmErrorText`).
10. **창 재탐지 버튼** (A5, `SettingsService.reprobeContextWindows()`) — 4번이 기동 시 1회라 값이 낡는다. 고치는 시점을 **사람이 정하게** 한 이유는 예산이 스스로 움직이면 재현이 안 되기 때문이다. 2026-09-28 에 **컨텍스트 초과 시 자동 재탐지**(`ContextWindowRefresher`)가 더해졌다 — 초과는 "기록된 창이 틀렸다"는 서버의 증언이라 근거가 있고, 커진 창은 초과가 나지 않으므로 여전히 버튼의 몫이다. 2026-10-19 부터 채팅 스트리밍의 초과도 재탐지를 건다(`LlmRouter.contextOverflowObserved()`).
11. **`max-tokens` 핫 편집** (A6, `MaxTokensCappingChatModel` 이 `IntSupplier` 로) — 페이지에 마지막 남은 조회 전용 값이었고, 하필 이 §의 거의 모든 계산이 파생되는 값이었다.

**남은 이슈**

- **자리가 `AnswerService` 인 이유**: 라우터는 호출을 불투명한 클로저로 받아 프롬프트 안을 못 보므로 무엇을 버릴지 아는 곳은 프롬프트를 조립하는 여기뿐이다. 라우터의 프로바이더 순회와는 층이 달라 서로 방해하지 않는다 — 저쪽이 "다른 곳에 물어본다"면 이쪽은 "더 작게 물어본다"다. 답변(블로킹·스트리밍·PROGRESSIVE)과 **검증** 양쪽에 걸린다.

> 해소된 이슈 7건의 경위와 근거 → [PITFALLS.md](PITFALLS.md#626-컨텍스트-초과-대응--창-인지--입력-예산--완료-2026-09-01)
---

### 6.20 사용자별 LLM 사용량 쿼터 🔵 미착수 — 멀티유저 활성화 시 후속 (우선순위 2)

**현재 코드 확인 (2026-07-04 재확인)**:
- `llm_usage.user_id` 컬럼 자체는 **이미 존재**한다(Flyway V1 — 당시엔 `LlmUsageRepository.init()`의 런타임 `ALTER TABLE ... DEFAULT 'anonymous'`도 있었다, EDIT.md #6에서 발견). 하지만 `record(String provider, long in, long out)`에 `userId` 파라미터가 없고 `getByPeriod/getDaily/usedProviders/deleteByProvider` 등 모든 조회 메서드도 이 컬럼을 참조하지 않아 **모든 행이 영구히 'anonymous'로 고정** — 사실상 죽은 컬럼이다. **실제 `llm_usage`는 여전히 프로바이더 단위 집계**이며, 사용자별 쿼터를 하려면 이 컬럼을 실제로 채우거나(아래 B안) conversation_turns 기반(A안, 권장)으로 별도 집계해야 한다.
- 집계 조회는 provider별만 존재 → 사용자 단위 "오늘 전체 토큰 합" 쿼리가 없음.
- `AnswerService.execute(AgentState)`(진입점)와 `AgentService.chat()`에 쿼터 게이트가 없음. `ThreadContext.userId()`로 사용자 식별은 가능.

**설계 (권장: 사용량 테이블 분리, 스키마 변경 최소)**:
1. **집계 소스 결정** — 두 안 중 택1.
   - (A) `conversation_turns`에 이미 `input_tokens`/`output_tokens`/`user_id`가 있으므로 **채팅 토큰은 여기서 사용자별 일일 합계**를 구할 수 있음(`SELECT SUM(input_tokens+output_tokens) FROM conversation_turns WHERE user_id=? AND asked_at >= date('now')`). 별도 테이블 없이 채팅 쿼터 구현 가능 → **1차 권장**.
   - (B) `llm_usage`에 `user_id` 축 추가(PK 확장 = SQLite 테이블 재생성) — provider×user 교차 집계가 필요할 때만. 현 요구엔 과함.
2. `QuotaService.checkDailyTokenQuota(userId)` 신설 — (A) 쿼리로 일일 합계 조회 → `app.quota.daily-token-limit`(신규 프로퍼티, 0=무제한) 초과 시 `QuotaExceededException`(신규).
3. **게이트 위치** — `AgentService.chat()` 진입 직후(`PromptInjectionGuard.validate()` 다음). 스트리밍은 `StreamingAgentService.run()` 진입에도 동일 적용.
4. `GlobalExceptionHandler`에 `QuotaExceededException` → 429(RFC 9457 ProblemDetail, `RAG-QUOTA-001`) 매핑. 채팅 UI(HTMX)엔 한도 초과 알림 프래그먼트.
5. 프로퍼티: `app.quota.enabled`(기본 false), `app.quota.daily-token-limit`. `AppProperties`에 `QuotaConfig` record + `quotaSafe()` null 가드.

**완료 기준**:
- 사용자의 당일 누적 토큰이 한도 초과 시 채팅이 429로 차단되고 `Retry-After`(자정까지) 안내가 표시된다.
- `app.quota.enabled=false`(기본)이면 기존 동작 회귀 0.
- 쿼터 집계가 스트리밍/블로킹 경로 모두에서 일관 적용된다.

---

### 6.21 소형(경량) LLM 분리 — 태스크별 모델 라우팅 + 멀티 LLM 처리량 확장 ✅ 완료

추론이 필요 없는 고빈도 백그라운드 호출(키워드+맥락 추출·대화 요약·제목 생성·MultiQuery 확장)을 신규 `TaskType.MICRO_TEXT`(`LIGHT_TEXT`/`BOTH`에 폴백)로 분리해 500MB급 소형 로컬 모델로 오프로딩 — 분류·직답은 품질 유지를 위해 큰 모델에 남김. 임베딩은 `LoadBalancingEmbeddingModel`(다중 엔드포인트 least-in-flight)+서브배치 병렬화로 처리량 확장, 대화 응답 쪽은 §6.12 재사용. **전부 opt-in(미등록 시 기존 동작 그대로) → 회귀 0**. 설정·런북: [LLM_ROUTING.md §9](LLM_ROUTING.md) · [OPERATOR_MANUAL §3.2·§5.4](OPERATOR_MANUAL.md).

**주의**: 임베딩 다중 엔드포인트는 **동일 모델·차원이어야 한다**(섞으면 인덱스 손상). 소형+대형 co-located 시 VRAM/RAM 합산 확인.

**실측 게이트 — 부분 검증으로 종료**: 라이브 듀얼티어로 §10.7.5 하네스 실행 시 색인된 골든셋 6건 전부 recall@10=1.0 — 소형 모델 라우팅이 검색 품질을 회귀시키지 않음을 확인. 전체 baseline(0.962) 정식 대조는 코퍼스에 arch/sample 문서가 미색인이라 보류(§6.21과 무관한 코퍼스 갭) — 재색인 후 `mvn test -Dtest=SearchQualityEvaluationTest -Dsearch-eval.enabled=true`로 재현 가능.

### 6.29 호출 지점별 생각(추론) 수준 제어 ✅ 완료 2026-10-07 (1~6단계) — 설계 2026-10-02

**목표**: LLM 호출 지점(사이트) 22곳마다 모델의 생각(reasoning)을 **끔/낮게/중간/높게** 중 하나로 정하고 `/settings`(관리자 전용)에서 핫 편집한다 — 채팅 답변(RAG S/N/C · Direct S/N · 메타), 응답 검증, 분류·독립화·확장·리랭크, 답변 뒤 보강, 인덱싱(키워드+맥락 · MD 교정 · TXT→MD · 이미지), 관리자 도구 전부.

**결과** — 6단계 실측(llama.cpp b11433 + gemma-4-E2B, 같은 입력을 끔/낮게로 번갈아 부름): 생각이 이득을 준 곳은 **응답 검증**(근거 없는 답을 잡는 비율 6% → 44%)과 **응용(C) 답변의 코드**뿐이었고, 나머지는 같은 품질에 짧은 호출이 10~40배, 채팅 답변은 첫 글자가 11~34초 느렸다. 측정은 그 셋(`EVAL`·`EVAL_CREATIVE`·`ANSWER_RAG_C`)만 낮게를 권했지만 **출하값은 속도를 택해 22곳 전부 끔**이다(운영 결정 2026-10-07 — `application.properties`·`ThinkingSite.shippedDefault`, `ThinkingSiteTest` 가 고정). 사이트별 수치·하네스·재현 방법은 [THINKING_EVALUATION.md](THINKING_EVALUATION.md).

**먼저 정해 둔 사실** (2026-10-02 확인, 10-07 갱신): 주력인 llama.cpp + gemma-4·Qwen3 는 `chat_template_kwargs.enable_thinking` 으로 **켬/끔만** 받는다(`reasoning_effort` 는 무시 — 낮게·중간·높게가 모두 켬으로 접힌다). 단계가 의미를 갖는 것은 gpt-oss(끌 수 없고 low 가 최저)와 원격 추론 모델(표준 `reasoning_effort`)이고, LM Studio 는 문서화된 스위치가 없다. b11433 부터는 요청 단위 `reasoning_budget_tokens` 도 받지만 **구현하지 않기로 했다**(열린 항목 (a)). 받을 수 없는 단계는 접어서 보내고 화면에 접혔다고 보인다 — 받은 것처럼 보이는 손잡이는 만들지 않는다(§10.10 큐레이션 요약·키워드의 거짓 손잡이 선례).

*상세 설계 원문(화면 목업·칸의 정의·배지 조건표·사이트별 실측 표·단계별 기록)은 커밋 `02f308b`, 2026-10-19 재압축 전 판본은 커밋 `c9a9ec4` 의 PLAN.md 에 있다. 동작 명세는 CLAUDE.md(`ThinkingSite`·`ThinkingControlChatModel`·`ThinkingBudget`·`AnswerStreamer`·`ThinkingPreviewService`), 깨지기 쉬운 곳은 PITFALLS("생각 수준 실측과 출하 기본값" 외), 운영은 OPERATOR_MANUAL("추론 모델의 생각 수준"·"생각(추론) 수준 카드"·"권장 프로파일").*

#### 설계 (요약 — 번호와 (가)~(자) 라벨은 소스 주석이 가리키는 자리다)

- **① 세 축을 분리한다** — 호출부는 **사이트만** 표시하고(`ThinkingSite`, 코드 고정), 수준은 설정에서 읽고(`ThinkingLevel` 넷뿐 — "서버 기본값" 수준은 앱이 모르는 값이라 두지 않는다. 생각 제어를 받을 수 없는 서버는 프로바이더의 성질 `NONE` 으로 옮겼다), 그 수준을 어떤 필드로 바꿀지는 **프로바이더가 정해진 뒤** 데코레이터가 정한다(`ThinkingDialect` = `app.llm.providers[n].thinking-dialect`, 재시작 필요 — 받을 프로바이더를 라우터가 나중에 정하므로 `MaxTokensCappingChatModel` 과 같은 이유).
- **② 호출 지점과 출하값** — 22곳, 출하값은 위 결과대로 전부 끔. 측정하지 못한 `SUMMARY`·이미지 셋도 끔이다(열린 항목 (f)). 서버를 생각 끔으로 띄운 배포에서도 낮게인 사이트는 요청의 `enable_thinking=true` 가 서버 기본값을 덮어 생각한다(검증이 한 번에 2~14초 → 45~60초).
- **③ 수준 → 필드 변환** — `AUTO`(지정하지 않으면: LOCAL 이면 `TEMPLATE_KWARGS`, 아니면 `NONE`) · `NONE`(싣지 않음 — 서버가 정한다, 생각 여유도 더하지 않는다) · `TEMPLATE_KWARGS`(`enable_thinking` — 세 단계가 같은 값으로 접힘) · `TEMPLATE_KWARGS_EFFORT`(gpt-oss — 끔이 `low` 로 접힘) · `OPENAI_EFFORT`(최상위 `reasoning_effort`, 끔은 `none`). 접힘 여부는 `dialect.effective(level)` 하나로 화면과 실제 전송이 함께 계산한다.
- **④ 출력 예약 — 이 기능에서 가장 위험한 부분.** 생각 토큰도 `max_tokens` 를 먹으므로 상한이 작은 호출은 생각을 켜는 순간 빈 응답이 나고, 반대로 상한을 넉넉히 올리면 `max_tokens` 가 예약이라 입력(검색 문서·이력·검증 발췌)이 먼저 잘린다. 그래서 `예약 = base + clamp(C − base, 0, h)`, `C = min(프로바이더 max-tokens, 창 × 25%)`(창을 모르면 프로바이더 max-tokens), `h` = 낮게 1,024 · 중간 2,048 · 높게 4,096(켬으로 나갈 때만) — **더하기만 하고 기본 예약은 깎지 않는다**. `ThinkingBudget.compute()` 하나를 요청(`ThinkingControlChatModel` 이 받는 프로바이더가 정해진 뒤 더한다)·입력 예산을 미리 재는 일곱 자리·미리보기가 함께 부르고, 재작성 사이트(`MD_CORRECT`·`TXT_TO_MD`)는 여유를 깎지 않고 조각을 줄인다(`PromptBudget.rewriteInputChars`). 상수(낮게 512 → 1,024, `expectedOutputTokens`)는 6단계 실측으로 정했다.
- **⑤ 스트리밍** — 채팅 답변(`stream=true`)은 `ChatModel` 체인을 통째로 우회하므로 `AnswerStreamer` 하나가 체인이 하던 일을 같은 함수로 한다: 수준 싣기(`ThinkingControl.applyTo`) · 생각 델타는 화면에 내보내지 않고 **활동으로만**(유휴 워치독 + "모델이 생각하는 중... (N초)") · 첫 청크 전의 거부만 한 번 재시도 · 관측 · 실제로 나간 본문의 curl 로그. `provider.openAiApi()` 를 다른 곳에서 부르면 빌드가 막는다.
- **⑥ 의도를 나르는 방법** — 블로킹은 `ThinkingControl.mark(builder, site)` 가 `extraBody` 의 내부 키(`__rag_thinking_site`)에 사이트를 싣고 체인 맨 바깥의 `ThinkingControlChatModel` 이 **항상** 그 키를 걷어낸 뒤 변환을 싣는다. 옵션을 넘길 수 없는 프레임워크 호출(`MultiQueryExpander`)은 `ThinkingSiteChatModel` 래퍼가 표시한다. dialect 는 이름 키 레지스트리(`ProviderThinkingDialects`)에 둔다. 서버가 필드를 거부하면 (프로바이더, 필드)로 기억하고 빼서 한 번 다시 보낸다 — 차단 없음.
- **⑦ `/settings` 카드 — 관리자 전용 · 수준별 예산 미리보기.** 수준을 **고르기 전에** 그 호출에서 무엇이 얼마나 바뀌는지 숫자로 본다. **(가) 권한** — 읽기까지 관리자만, 엔드포인트는 전부 `/admin/settings/**`(화면에서 숨기는 것은 보안이 아니다) · **(나) 구성** — 프로바이더 표의 "생각 제어" 열(사용자 설정 적용 · 서버 설정 사용 · Unknown), 접힌 채 시작하는 카드, 기준 줄, 그룹 표 6개, 행마다 [자세히] · **(다) 칸** — 수준·실제 전송·받는 프로바이더·출력 예약·입력 예산·뜻하는 것·생각 자리(= 예약 − `expectedOutputTokens`)·최악 소요·상태 · **(라) "뜻하는 것"** 은 사이트 종류별(문서 k/top-K · 이력 상한 · 발췌 k/top-K · 후보 수 · 조각 크기 …, `shapeOf` exhaustive switch) · **(마) 상태 배지는 막지 않는다**(저장은 언제나 된다 — 응답 자리 없음·잘림·여유 깎임·입력 축소·스위치 무시됨·타임아웃 넘음 등, 임계값은 `ThinkingPreviewService` 상수) · **(바) 미리보기 = 런타임** — 숫자를 직접 만들지 않고 호출부가 쓰는 함수를 그대로 부른다(`ThinkingPreviewParityTest`) · **(사) 상호작용** — 네 수준을 서버가 미리 계산해 렌더하고 드롭다운은 보이기만 한다(JS 계산 없음), 저장은 행을 새로 계산해 교체, 창 재탐지는 `HX-Trigger: thinking-preview-stale` · **(아) 설정 키** — `llm.thinking.<id>`(`Spec.Kind.CHOICE`, `ThinkingSite` 에서 생성), 매 호출 읽기, 틀린 줄은 출하값 + 기동 WARN(`ThinkingStartupReport`), 환경변수 없음 · **(자) 테스트** — 권한·기본값·미리보기=런타임·골든(16k·8k)·불변식·컨벤션.
- **⑧ 관측** — `ThinkingObservations` 가 (사이트, 프로바이더, 수준)별 최근 50회를 **숫자로만** 메모리에 둔다(재시작 시 초기화 — 프롬프트·생각 본문에는 검색 문서가 들어 있다). 블로킹은 생각 본문을 받지 못해 출력이 답 추정의 두 배를 넘고 64 이상 남을 때만 그 초과분을 생각으로 센다(긴 출력 앞의 짧은 생각은 놓친다). **관측만 하고 예산을 바꾸지 않는다** — 같은 요청이 표본 수에 따라 다른 예산을 받으면 재현이 안 된다.

**작업 단계 — ✅ 전부 완료**: 1 골격·출하값·관측(10-02, 기동 리포트를 앞당김) · 2 블로킹 호출부 배선 + 라우팅의 단일 출처(10-02, `ThinkingSiteConventionTest`) · 3 스트리밍 `AnswerStreamer`(10-02 — **채팅 스트리밍의 축소 재시도(§6.26-9)가 실서버에서 한 번도 발동하지 않았던 것**을 `LlmErrorText` 로 고침) · 4 `ThinkingBudget`(10-02 — Direct 블로킹 경로의 이력 안전망이 스트리밍 예약을 빼던 어긋남도 고침) · 5 `/settings` 미리보기(10-07 — `settings.html` 의 툴팁 스크립트가 한 번도 실리지 않던 것도 고침) · 6 실측 → 출하값(10-07, `ThinkingLevelEvaluationTest` + `scripts/thinking_eval_summary.py`). 6단계의 발견(독립화는 생각이 켜지면 원문을 그대로 돌려주는 일이 늘고, 답변 뒤 보강은 낮게면 칩이 뜨지 않으며, 질의 확장은 이 코퍼스에서 recall 을 올리지 못한다 등)과 한계(모델·서버·코퍼스 하나, 작은 표본 — 크기보다 방향)는 THINKING_EVALUATION.md.

**열린 항목**: (a) ~~요청 단위 생각 토큰 예산~~ — 10-07 b11433 이 받는 것을 확인했고 **10-12 측정 뒤 구현하지 않기로 결정**했다(예산 256·512·1,024 에서 걸러야 할 답 19건 중 0·2·5건 — 예산을 줄인 만큼 검증의 탐지가 사라져 비용 절감 수단이 되지 못한다, [THINKING_EVALUATION.md §3.7](THINKING_EVALUATION.md)). 다시 열 신호는 수준을 올린 배포에서 `finish=length` 로 빈 검증·답변이 관측될 때이고, 그때의 용도는 **안전 상한**이다. 측정 프록시(`ReasoningBudgetProxy`)와 하네스 옵션은 남긴다 · (b) `SUBMISSION_ENRICH` 를 `KEYWORD_CONTEXT` 에서 분리할지 · (c) 응답 모드별 수준을 메시지마다 고르게 할지 — 하지 않는 쪽이 기본(축이 늘면 버블 표기와 재사용 모양 복사까지 번진다) · (d) ~~출력 상한을 정하지 않는 호출부~~ — **10-12 해소**(분류·리랭크·제목·이미지 둘은 자기 상한 2,048, 요약은 `max-summary-chars` + 1,024; 남은 것은 프롬프트를 스스로 만드는 질의 확장 하나 — PITFALLS "짧은 호출의 출력 상한") · (e) **유휴 워치독은 블로킹 호출을 보지 못한다** — 분류·확장·검증은 리스너 이벤트 없이 도는 블로킹 호출이라, 생각을 켜고 `app.sse-idle-timeout-seconds` 를 크게 낮춘 배포에서 끊긴다(5초 실측: 확장은 원문 검색, 검증은 판정 없음, 라우터가 그 인터럽트로 5초 차단). 기본 300초에서는 문제없다. 고칠 방향은 블로킹 호출이 진행 중인 동안을 활동으로 보거나, 인터럽트로 끊긴 블로킹 호출을 차단에서 빼는 것(`isTimeoutLike` 와 같은 갈래) · (f) 측정 못 한 넷(`SUMMARY`·`IMAGE_DESCRIBE`·`IMAGE_TYPE`·`MD_CORRECT_VISION`)은 소형 모델 계층·비전 모델이 있는 배포에서 재고, 이득이 있으면 올린다 · (g) 더 큰 모델·원격 추론 모델에서 하네스를 다시 돌려 출하값과 상수를 재확인 · (h) 카드의 "⚠ 타임아웃 넘음"은 읽기 타임아웃만 본다(답변 뒤 보강의 화면 기다림 20~25초 같은 사이트별 한도는 모른다) · (i) 카드의 작은 한계 — 저장하면 펼친 [자세히] 가 접히고, 라우팅 모드별 줄은 저장된 수준으로만 계산한다(PROGRESSIVE 2차의 `QUALITY_FIRST` 포함).

---

## 7. Phase 4 — 확장 (조건부) 🔵 미착수

### 7.1 OAuth2 소셜 로그인 (가입 마찰 문제 발생 시)

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

Google/GitHub 제공자 등록. 가입 흐름은 **기존 폼 가입과 동등**하게 처리 (users 테이블 row 생성, `password_hash`는 NULL 허용).

### 7.2 PostgreSQL 마이그레이션 (SQLite 한계 신호 발생 시)

1. Flyway placeholder 도입: `${json_type}` → SQLite=`TEXT`, Postgres=`JSONB`
2. SQLite 데이터 덤프 → Postgres 임포트 스크립트 작성 (1회용)
3. 커넥션 풀: HikariCP 기본값 (max=10)으로 시작 → 부하 테스트 후 조정
4. Spring 프로파일 분리: `application-sqlite.properties` / `application-postgres.properties`

### 7.3 관리자 페이지 (부분 존재 → 확장)

> **일부 선반영 → §6.25**: 이 절의 "사용자/운영 관리" 중 **대화 관리**(전 사용자 대화 목록 조회·삭제 + 검색 진단 연결)는 no-auth 단일 운영자에서도 값어치가 있어 Phase 3 §6.25로 분리해 먼저 진행한다. 여기 남는 것은 **사용자 계정 관리**(목록·잠금 해제·강제 로그아웃)와 감사 로그 뷰 — 전부 `auth.enabled=true`가 전제다.

- `ROLE_ADMIN` 전용 경로 확장(현재 청크 관리 위주 → 사용자/운영 관리 추가). ※ DB `role` 값은 `ADMIN` 문자열, 인증은 `NoAuthAutoLoginFilter`가 no-auth 모드에서 첫 `ADMIN` 사용자로 자동 인증.
- 사용자 목록·상태(잠금/활성), 전체 LLM 사용량(§6.5~6.7과 연계), 감사 로그(`data/audit/audit.log` NDJSON) 조회 뷰.
- 강제 로그아웃·계정 잠금/해제(`SqliteUserDetailsService`의 `locked_until`/`failed_count` 조작 재사용).

---

## 8. Phase 5 — Vector Store 선택적 연동 ✅ 완료 + 확장 계획

### 8.1 목표

`app.vectorstore.type=chroma`(기본, Docker 필요) ↔ `sqlite-vec`(인프라 0추가, 로컬·저사양·폐쇄망)를 **설정만으로 전환**. 두 백엔드 모두 `VectorStoreProvider` 뒤에 숨고 `VectorStoreFacade`가 횡단 관심사(SAFE_VERSION 검증·유사도 임계값)를 유지. 백엔드 전환 시 재인덱싱 필요(벡터 비공유).

### 8.2 작업 단계 로드맵

| 단계 | 작업 | 산출물 |
|------|------|--------|
| Step 5.1 ✅ | VectorStoreProvider 추상화 (Chroma 무행위 리팩토링) | `VectorStoreProvider`, `ChromaVectorStoreProvider` |
| Step 5.2 ✅ | sqlite-vec 네이티브 확장 로딩 | `DataSourceConfig.configureSqliteVec`, `SqliteVecVerifier` |
| Step 5.3 ✅ | sqlite-vec 스키마 초기화 | `SqliteVecSchemaInitializer` |
| Step 5.4 ✅ | SqliteVecVectorStoreProvider 구현 | `SqliteVecVectorStoreProvider` |
| Step 5.5 ✅ | 백엔드 선택 스위치 (조건부 빈) | `VectorStoreProviderConfig`, Chroma 빈 가드 |
| Step 5.6 ✅ | 설정 외부화 (.env / docker-compose) | properties, `.env.example`, compose profiles |
| Step 5.7 ✅ | 데이터 이전 + 통합 검증 | 재인덱싱 절차, 단위·통합 테스트 |
| Step 5.8 ✅ | 관리자 페이지 백엔드 가시성 보강 (sqlite-vec) | `VectorStoreAdminView`(신규), `AdminService`에 `JdbcTemplate`/`AppProperties`/`ObjectMapper` 주입, `/admin` 백엔드 공통 상태 카드 + 청크 브라우징 패리티, `AdminService`·`AdminController` 테스트 |
| Step 5.9 ✅ | 태그 기반 검색 스코프(엄격 필터 + sqlite 후보확대 보정) | 업로드 다중 태그·채팅 태그 선택·백엔드별 엄격 필터·프리릴리즈 수동 초기화 런북 |
| Step 5.10 ✅ → ↩ 환원 | sqlite-vec 운영 DB 분리(최소 변경) | 운영 SQLite(`memory.db`)와 벡터 SQLite(`vector.db`)를 분리, DataSource/JdbcTemplate 2세트 구성, 무중단 롤백 스위치 — **배선 사고로 분리가 실효된 적이 없어 2026-09-28 단일 파일로 환원**(아래 본문) |

### Step 5.1~5.8 — 백엔드 추상화 + sqlite-vec 구현 ✅ 완료

세부 산출물은 위 로드맵 표 참고. 보완 메모: 5.3 차원 미설정 시 fail-fast. 5.4 upsert 미지원이라 add=DELETE 후 INSERT. 5.7 백엔드 전환은 항상 재인덱싱 필요(원본 보존, 무손실).

### Step 5.9 — 태그 기반 검색 스코프 ✅ 완료

업로드 시 다중 태그 저장 + 채팅에서 선택한 태그로 **엄격 AND 필터**(Chroma·sqlite-vec·하이브리드 공통). provider가 배열 포함 연산자를 지원하지 않아 `RetrievalService.execute()`가 `mergeRrf()` 직후 Java post-filter로 적용하고, 결과 부족 대비 `candidateK`를 선제 확대(`app.search-tag-candidate-multiplier`). 태그 소스는 `chunk_fts.doc_tags` 단일 컬럼(태그 제안 UI + 재인덱싱 자동복원 공용). 스키마 변경은 프리릴리즈 정책상 마이그레이션 없이 수동 초기화(OPERATOR_MANUAL §4.6).

### Step 5.10 — sqlite-vec 운영 DB 분리 ✅ 완료 → ↩ 2026-09-28 단일 파일로 환원

`SQLITE_VEC_DB_PATH` 설정 시 벡터/FTS 테이블을 별도 `vector.db`로 분리해 인덱싱 I/O와 운영 트랜잭션 락 경합을 감소(기본 비활성, 기존과 동일 동작). `vectorJdbcTemplate` 빈이 두 모드 모두 대응(분리 시 `vector.db`/아니면 `memory.db` 별칭)해 소비자 코드는 무변경, Chroma 경로는 완전 무영향. 파일 간 트랜잭션 원자성이 없어 인덱싱은 항상 벡터/FTS 먼저 → 레지스트리 마지막 순서로 커밋(고아 벡터는 재인덱싱이 덮어씀). 전용 DataSource도 pool=1+WAL+busy_timeout 동일 적용.

**환원 (2026-09-28).** 분리는 한 번도 실효된 적이 없다 — `vectorJdbcTemplate` 빈이 정의되는 순간 Boot 의 `JdbcTemplate` 자동설정이 물러나 운영 저장소까지 전부 `vector.db` 에 썼고(2026-09-04 확인), `memory.db` 는 Flyway 만 닿는 빈 파일, 초기화 스크립트는 반쪽, Flyway 이력과 실스키마는 서로 다른 파일이 됐다. 게다가 `QuestionReuseRepository.findSourcePreviewRows()` 가 운영 테이블과 FTS/벡터 테이블을 SQL 하나로 조인하게 되어, 배선만 고치면 기존 배포는 데이터가 사라진 것처럼 보이고 출처 목록은 `no such table` 로 깨진다. 측정된 적 없는 락 분리를 위해 그 조인 해소 + 운영 테이블 이전을 하는 대신 **DataSource 를 하나로 합쳤다**(`DataSourceConfig`): 기본은 `{DATA_DIR}/memory.db` 하나, 옛 스위치 값은 sqlite-vec 에서 **그 한 파일의 경로**로만 읽는다(그 배포의 데이터가 전부 거기 있으므로 데이터 이전 불필요). 이력 없는 옛 파일을 위해 `spring.flyway.baseline-version=3`(`FlywayBaselineTest`). `/admin` 카드는 파일 하나를 실제 DataSource 에서 읽어 표시, reset 스크립트는 `vector.db` 도 지운다. 성능 변화 없음(I/O 는 원래 한 파일·한 커넥션). 경위·함정은 [PITFALLS](PITFALLS.md#벡터-스토어-백엔드와-vecfts-datasource), 운영자 이름 정리 절차는 OPERATOR_MANUAL §6.3.1. 재개 신호는 부록 결정표.

---

## 9. Phase 6 — 폐쇄망(Air-gapped) / 노-도커 실행 지원 🟢 G1~G5 완료 (sqlite-vec 라이브 부팅은 운영 인수)

> 폐쇄망/노-도커 런북은 [OPERATOR_MANUAL.md §4.5](OPERATOR_MANUAL.md) 참조. 아래는 요약.

### 9.1 목표

(A) Docker 없이 실행, (B) 폐쇄망에서 sqlite-vec 단독 + 로컬 LLM(llama-server) 운영. Phase 5로 ChromaDB(유일 필수 Docker)를 제거할 수 있게 됐고, 프론트 자산은 webjar/로컬 번들이라 CDN 의존 0.

### 9.2 현황 (Phase 5 시점 이미 충족)

sqlite-vec 단독·CDN 0·로컬 LLM 채팅·외부 프로바이더 빈 키 자동 비활성·`LOCAL_ONLY` 모드·로컬 임베딩 엔드포인트·HTTP 직노출 옵션·Vision 기본 `strip`.

### 9.3 해결한 공백/함정 (G1~G4)

- **G1** — `LlmConfig`가 빈 키 프로바이더를 드롭하던 문제: LOCAL role은 빈 키여도 등록(`"no-key"` 치환)하도록 변경 → 키리스 llama-server 채팅 가능.
- **G2** — `app.embedding.dimensions=${EMBED_DIMENSIONS:}`(빈값→null 안전) 외부화 + `.env.example` 차원표. sqlite-vec DDL 전용이라 모델 실제 출력 차원과 정확히 일치 필수(미설정 시 fail-fast).
- **G3** — `app.llm.default-routing-mode=${LLM_ROUTING_MODE:COST_FIRST}`로 외부화 → `LOCAL_ONLY`로 외부 호출 명시 차단.
- **G4** — `USE_CADDY_REVERSE_PROXY_HTTPS` 오타(CANDY) 정리.

### 9.4 작업 항목

| 단계 | 작업 | 상태 |
|---|---|---|
| 6.1 | LOCAL role 키리스 허용 (`LlmConfig`) | ✅ |
| 6.2 | `EMBED_DIMENSIONS` 외부화 + 차원표 | ✅ |
| 6.3 | `LLM_ROUTING_MODE` 외부화 | ✅ |
| 6.4 | 폐쇄망 런북 (OPERATOR_MANUAL §4.5 + README 환경변수표) | ✅ |
| 6.5 | 무-외부호출 기동 인수 | ✅ (sqlite-vec 라이브 부팅은 운영 인수) |
| 6.6 | (선택) `USE_CADDY_…` 하위호환 별칭 | 🔵 미착수 |

### 9.5 폐쇄망 운영 전제 (요약)

빌드 산출물(fat-jar + JRE 21 + 호스트 arch vec0 바이너리 + 필요 시 tessdata) 반입, OCR/Vision은 옵션, TLS는 HTTP 직노출 또는 사내 역프록시/사설 CA, `.env`는 노-도커 시 자동 로드 안 됨(셸 export/기동 인자). 상세는 OPERATOR_MANUAL §4.5.

### 9.6 인수 결과

G1~G4 코드/문서 완료, G5(라우팅 계층의 외부 무선택)도 완료. vec0 필요한 sqlite-vec 라이브 부팅·실소켓 0 측정은 운영 인수로 남김.

---

## 10. Phase 7 — 검색 품질·성능 고도화 ✅ 완료

> 검색 파이프라인(`RetrievalService`: MultiQuery 확장 → 배치 임베딩 → 벡터 검색 → 가중 RRF 융합 → 하이브리드 BM25 → (opt-in) LLM 리랭크 → 태그 필터) 위에서 정확도·속도·메모리를 끌어올리는 증분 개선 17건. §10.7.5에서 구축한 recall@k/nDCG@k 평가 하네스로 실측 baseline(recall@10=0.962)까지 확보해 전체 종료.

| 항목 | 개선 | 핵심 산출물 |
|------|------|------|
| §10.1 Contextual Retrieval ✅ | 청크 임베딩·FTS 입력에 구조적+LLM 맥락 헤더 주입 | `SearchTextBuilder`, `KeywordExtractor` |
| §10.1-보완 임베딩 입력 정규화 ✅ | 마크다운 장식 제거, 저장/임베딩/프롬프트 3계층 분리 | `MarkdownNoiseNormalizer` |
| §10.2 가중 RRF ✅ | 벡터축 그룹 정규화 + 키워드축 가중치 외부화 | `RetrievalService.mergeRrf()` |
| §10.3 쿼리 임베딩 캐시 ✅ | Caffeine 캐시 데코레이터 | `CachingEmbeddingModel` |
| §10.4 한국어 FTS 트라이그램 ✅ | `unicode61`→`trigram` 무손실 자동 재구축 | `chunk_fts` |
| §10.7.1 리랭커 프리뷰 확장 ✅ | 200→500자 + 구조적 컨텍스트 헤더 | `RerankerService` |
| §10.7.2 하이브리드 기본 활성화 ✅ | `SEARCH_HYBRID_ENABLED` false→true | — |
| §10.7.3 2글자 질의 LIKE 폴백 ✅ | trigram 미만 질의 존재-신호 보강 | `KeywordSearchRepository` |
| §10.7.4 유사도 임계값 과조회 ✅ | `topK×2` 조회 후 재절단 | Chroma/sqlite-vec 양쪽 |
| §10.7.5 검색 품질 평가 하네스 ✅ | 골든셋 26문항 + recall@k/nDCG@k | `evaluation/` |
| §10.8.1 MultiQuery 지연 병렬화 ✅ | min-length 상향 + 원본검색 병렬 실행 | `RetrievalService.execute()` |
| §10.8.2 키워드 추출 배치화 ✅ | 청크 N개를 LLM 1콜로 | `KeywordExtractor` |
| §10.8.3 SQLite 배치삽입 트랜잭션화 ✅ | 배치 2개 → 트랜잭션 1개 | `SqliteVecVectorStoreProvider` |
| §10.8.4 SHA-256 중복 해싱 제거 ✅ | `precomputedSha256` 전달 | `IndexRequest` |
| §10.8.5 파생 텍스트 중복계산 제거 ✅ | `SEARCH_TEXT` 1회 계산 후 공유 | `SearchTextBuilder` |
| §10.9.1 Chroma 미사용 임베딩 제외 ✅ | 검색 응답 Include 필드 축소 | `ChromaVectorStoreProvider` |
| §10.9.2 벡터 BLOB 직렬화 ✅ | JSON 텍스트 → little-endian float32 BLOB | `toVectorBlob()` |
| §10.9.3 sqlite-vec 스트리밍 삽입 ✅ | 서브배치 임베딩 직후 즉시 삽입 | `SqliteVecVectorStoreProvider.add()` |
| §10.9.4 인덱싱-검색 캐시 분리 ✅ | `unwrapForIndexing()` + SHA-256 캐시 키 | `CachingEmbeddingModel` |

### 10.1~10.4 — Phase 7-A·B·C 정확도 기반 개선 ✅ 완료

세부 산출물은 위 표 참고. 보완 메모: §10.1 저장 텍스트는 원문 불변(맥락 헤더는 임베딩/FTS 입력에만). §10.1-보완은 재인덱싱 필요. §10.4 trigram 전환은 2글자 단독어를 여전히 놓쳐 §10.7.3 LIKE 폴백으로 별도 보완했다.

### 10.5 검토 후 제외 (Phase 7-D 취소)

원래 Phase 7-D(인프라 투자)로 묶였던 아래 3건은 재검토 결과 **범위 제외**한다 — 삭제가 아니라 판단 근거를 남겨, 아래 "재개 신호"가 실제로 관측되면 이 기록을 근거로 다시 꺼낸다.

| 제외 항목 | 원래 개선안 | 제외 사유 | 재개 신호 |
|---|---|---|---|
| **sqlite-vec 배치 검색 단일 스캔** | vec0 brute-force KNN(O(n))을 다중 쿼리 1회 스캔으로 최적화 | 현 코퍼스 규모에선 병목이 임베딩 배치 생성(1 HTTP)이고 JDBC 루프는 수 ms라 체감 이득이 작다(§11 "searchBatch N회" 리스크 항목과 동일 판단) | 대규모 코퍼스에서 검색 지연 실측 악화 |
| **Cross-Encoder 리랭커** | LLM 리랭커를 ONNX bge-reranker 등 로컬 cross-encoder로 교체 | ONNX 런타임/모델 도입 + 폐쇄망 모델 파일 조달 비용이 크고, 현 opt-in `Optional<RerankerService>` LLM 리랭커로 충분. **인터페이스 유지 구조라 필요 시 구현만 교체 가능**(지금 만들 이유는 없음) | LLM 리랭크 정확도/지연 불만이 실사용에서 반복 보고 |
| **시맨틱 응답 캐시** | 질문 임베딩 유사도 > 임계값이면 캐시 답변 반환 | stale 답변 위험 + 무효화 복잡도(재인덱싱·버전 변경·§6.8 DISLIKE 연동) 대비 이득이 불확실 | FAQ성 반복 트래픽이 지배적이고 지연이 문제화될 때 |

> **§10.10과의 관계**: 아래 §10.10(좋아요 기반 큐레이션 Q&A)이 "시맨틱 응답 캐시"의 stale 위험을 우회한 변형이다 — 캐시처럼 답변을 verbatim 반환하지 않고 인간이 편집·검수한 Q&A를 **가중 RRF 축의 근거**로만 주입하므로 무효화 복잡도가 낮다. 순수 자동 캐시(위 항목)는 여전히 재개 신호 대기.

### 10.7 검색 정확도 마무리 (Phase 7-E) ✅ 완료

세부 산출물은 위 표 참고. §10.7.5에서 실 코퍼스 골든셋(NEXCORE 문서 3종, 26문항) + recall@k/nDCG@k 평가 하네스(`src/test/.../evaluation/`, `-Dsearch-eval.enabled=true` 게이팅)로 **2026-07-16 실측 baseline: mean recall@10=0.962(25/26), nDCG@10=0.810**을 확보 — §10.7.2·§10.7.3의 무측정 결정을 데이터로 재검증했다. 유일한 미스는 `sample-02`("DM 간 호출 가능 여부"); 이 수치를 향후 검색 튜닝 변경의 회귀 비교 baseline으로 삼는다. **2026-10-12 재측정**([SEARCH_EVALUATION.md](SEARCH_EVALUATION.md)) — 코퍼스가 문서 23종(청크 2,388개)으로 커진 뒤에도 recall@10 0.962(`.env` 구성, nDCG 0.803~0.808) · 1.000(운영 설정 + 질의 확장)으로 기준선과 같다. 그 전에 골든셋의 정답 문자열 6개를 바로잡았다(문서 교정으로 표현·서식이 바뀐 5개 + 새 문서에 더 직접적인 답이 생긴 1개 — 고치기 전에는 0.923 으로 보였다). 하네스는 이제 매번 코퍼스 점검(정답이 코퍼스에 있는 케이스 수 = recall 상한)을 찍는다. **2026-10-13 키워드 검색어 개선 뒤 기준선은 두 구성 모두 recall@10 1.000(nDCG `.env` 0.844 · 운영 0.902)** — 키워드 축이 질문 토큰을 조사가 붙은 그대로 구문 검색해 3글자 이상 토큰의 26% 가 코퍼스에서 0건이었고, 조사·어미를 뗀 어간을 원래 형태 옆에 더해 고쳤다(SEARCH_EVALUATION.md §3.5). **질의 확장(MultiQuery)은 켠 채 유지하기로 했다**(2026-10-12) — 이 코퍼스에서 되찾는 것은 운영 설정의 1건(arch-11)뿐이지만, 대가(검색당 약 1.1초 + LLM 1회)가 답변 생성(12~50초)에 비해 작고 끈다고 얻는 것이 없다. 코퍼스가 더 커져 표 한 행 같은 작은 청크가 대형 문서에 밀리면 확장의 몫이 커질 수 있다.

### 10.8 검색·인덱싱 속도 개선 (Phase 7-E) ✅ 완료

세부 산출물은 위 표 참고. 보완 메모: §10.8.2 배치 실패 시 곧장 개별 TF 폴백. §10.8.3 트랜잭션 결합으로 고아 vec_embeddings 행 방지.

### 10.9 메모리 최적화 (Phase 7-E) ✅ 완료

세부 산출물은 위 표 참고. 보완 메모: §10.9.1 벡터를 그대로 되돌려 쓰는 `updateTags()`는 영향 없음. §10.9.2 BLOB 직렬화는 기존 데이터와 호환(백필 불필요) — 폐쇄망 vec0 빌드의 BLOB 미지원 가능성은 낮지만 백엔드 전환 시 문서 1건으로 우선 확인 권장(OPERATOR_MANUAL.md 기록). §10.9.4 캐시 키는 SHA-256 해시로 고정 크기화.

### 10.10 좋아요 기반 큐레이션 Q&A 지식화 ✅ ①②③④ 전체 완료 (유입 경로는 §10.11 로 대체됨)

Phase 7의 원래 17건 완료 **이후** 추가된 설계. 좋아요(👍)한 답변을 `curated_qa`로 스냅샷 → 예약 네임스페이스 `"curated"`에 임베딩 → 검색 시 **가중 RRF 축**으로 융합 → 본인(채팅 인라인) 또는 관리자(`/admin` 카드)가 편집·삭제. §10.5에서 제외한 "시맨틱 응답 캐시"의 **인간 검수 가능 버전** — verbatim 반환이 아니라 검색 축의 근거로만 주입하므로 stale 위험·무효화 복잡도가 낮다. 신규 프로바이더 메서드 없이 기존 인프라(§10.1 임베딩≠저장, §10.8.5 `SEARCH_TEXT` 오버라이드, `/admin/chunks` 편집 패턴, 소유권 체크)를 재사용. 구현 상세는 CLAUDE.md `CuratedQaService`/`CuratedQaRepository` 항목, 운영은 [OPERATOR_MANUAL §6.7·§7.5](OPERATOR_MANUAL.md), UI는 [UI.md](UI.md).

**확정된 정책**
- 가시성=전체 공유. 답변은 verbatim 반환이 아닌 **LLM 근거로 주입**(오답 증폭 방지, `/admin`에서 가중치·편집·삭제로 통제)
- ~~편집 권한=본인 OR 관리자~~ — §10.11 에서 바뀌었다: 본인 편집은 **지식 제안 페이지**에서만 하고(채팅 인라인 연필 제거), 관리자는 `/admin` 에서 모든 항목을 편집한다
- **대화 삭제 시 큐레이션도 함께 회수**(§6.25에서 정책 변경) — 최초 정책은 반대였다("유지, 캐스케이드 안 함": 개인 대화 정리가 공유 검색 품질을 조용히 떨어뜨리는 것을 막기 위함). 바뀐 이유는 대화가 사라진 뒤에도 그 답변이 검색 근거로 남는 쪽이 더 혼란스럽고, 턴 단위 삭제(`deleteTurn`→`onUnlike`)는 처음부터 회수하고 있어 두 경로의 동작이 갈려 있었기 때문. 구현은 `CuratedQaService.onThreadDeleted()`이고 **사용자 경로와 관리자 경로가 함께** 호출한다. 승인된 청크 추가 제안(`origin='manual'`)은 대화 소속이 아니므로 제외된다. 열린 항목 (e)(프라이버시 트레이드오프)는 이 변경으로 해소됐다
- **문서 삭제와는 연동 없음**(열린 항목 (b))

**열린 항목**: ~~(a) 큐레이션 축 similarity threshold 별도화~~ — **2026-10-13 해소**: 벡터로 찾힌 항목은 `app.search-curated-qa-min-similarity`(기본 0.5, 문서 임계값보다 높게), 키워드로만 찾힌 항목은 질문 내용어 둘을 요구한다(`RetrievalService.gateCurated()` — 풀이 작아 문턱만 넘으면 그 축 1위가 곧 RRF 상위가 되기 때문) · (b) doc_id 인용 추적 + 문서 삭제 시 재검토 플래그 — **전제가 바뀌었다**: 처음엔 턴별 출처를 저장하지 않아 구조적으로 불가였지만, 지금은 `turn_source_ref`(§6.23)가 좋아요 출신 행의 출처 청크·`doc_id` 를 갖는다(손으로 쓴 제안에는 출처 문서가 없다). 연동은 미착수 — stale 인용이 실사용에서 보고되면 착수 · ~~(c) BM25 축 편입~~ — **2026-09-07 해소**: 임베딩 성공 시 같은 청크를 FTS 에도 색인하고 요약을 그 검색 텍스트 앞에만 붙인다(`CuratedQaService.indexFts()`, 예약 네임스페이스 `"curated"` 로 한 번 더 묻는다 — CLAUDE.md Key Constraints) · ~~(d) 회귀 검증은 §10.7.5 골든셋(recall@10=0.962) 재측정~~ — **2026-10-12 해소**: 0.962 그대로(SEARCH_EVALUATION.md) · ~~(e) thread 삭제 시 큐레이션 유지의 프라이버시 트레이드오프~~ — §6.25에서 회수로 정책을 바꿔 해소.

---

> **§10.11 · §10.12 · §10.13 구현 순서 — `10.11 → 10.13 → 10.12`** (셋 다 완료)
>
> 셋은 독립적으로 보이지만 **§10.13 이 §10.12 의 입력을 바꾼다.** §10.13 은 Direct 답변(문서에 근거하지 않은 텍스트)을 이력에 훨씬 많이 남기고, §10.12 는 그 이력을 재료로 검색어를 만든다. §10.12 를 먼저 만들면 **깨끗한 환경에서 통과시킨 뒤 §10.13 이 조건을 바꿔 놓는다** — 오염(§10.12 열린 항목 (b))이 나중에 드러난다. 순서를 뒤집으면 독립화를 만들 때 그 조건에서 바로 검증하게 된다.
>
> §10.11 이 먼저인 이유는 다르다. 나머지 둘과 얽히지 않으면서 **가장 위험한 구멍**(무검토 유입 — 문서를 하나도 안 본 Direct 답변이 좋아요 한 번에 전체 검색 지식이 된다)을 막는다. 기능 개선이 아니라 사고 예방이라 앞에 둔다.
>
> **§10.13 의 1단계(`DN` 도 `## 요약` 을 내게 한다)는 프롬프트 한 곳 수정이라 언제든 단독으로 나갈 수 있다** — 그 자체로 지금의 요약 경로에서 LLM 호출과 300자 절단을 없앤다. 나머지를 미루더라도 이것만 먼저 넣는 선택지가 있다.

### 10.11 좋아요 → 지식 제안 경유 (§10.10 유입 경로 대체) ✅ 완료 (2026-09-02)

**무엇을 왜.** 검색 코퍼스로 들어가는 문이 둘인데 경비가 정반대였다 — 제안은 관리자 승인 필수, 좋아요는 게이트 없이 3초 뒤 반영. 그 구멍의 가장 날카로운 모양이 **문서를 하나도 안 본 Direct 답변이 좋아요 한 번에 전체 검색 지식이 되는 것**이었다. `direct_mode` 가드를 박는 대신 **문을 하나로 모았다** — 좋아요는 제안 폼을 열어 줄 뿐이고, `curated_qa` 에 쓰는 경로는 관리자 승인 둘(`createFromSubmission`/`createFromLikedTurn`)뿐이다.

**유지해야 할 결정**: **채팅 = 감상, 제안 페이지 = 지식 관리**(채팅은 큐레이션 상태를 표시하지 않는다. 좋아요 확인의 "예"는 좋아요 + 프리필 이동, "아니오"는 좋아요 취소 — 중간 상태를 만들지 않는 것이 이 화면의 계약) · **저장 모양이 출처마다 다르다**(손으로 쓴 제안 = 승인 시 N개 행, 좋아요 출신 = turn 키 행 하나 → 임베딩 시점에 벡터 N개 — 좋아요 출신을 `insertManual` 로 태우면 `UNIQUE(source_turn_id)`·삭제 회수·재승인이 함께 죽는다) · 승인 시 `source_submission_id` 필수(제안의 상태가 전부 그 컬럼으로 세어진다) · **수정은 등록본을 내리지 않고 재승인이 교체**한다(정책 3 — 제안은 이전 행을 먼저 내리고 새 id, 좋아요 출신은 제자리 upsert: 먼저 내리면 백그라운드 벡터 삭제가 방금 쓴 벡터를 지운다) · 좋아요 취소는 회수하지 않고 턴/대화 삭제는 **무조건** 회수한다 · 좋아요 출신은 pending 상한에서 제외한다(버튼에는 오류를 띄울 자리가 없다 — 턴당 살아 있는 제안 1건이 남용을 막는다). **정책 변경 3건 전부 적용**: ① 큐레이션 검색축 통합(`app.search-submission-weight` 제거) ② C 개방 + S 사유 교체(`allowsCuration()` → `allowsSubmission()`) ③ 수정 중에도 등록본 유지.

**계획이 틀렸던 지점**: 연결(`source_submission_id`)이 빠진 증상은 "승인 직후 회수됨"이 아니라 **청크 0개짜리 '등록 완료'** 였다(전체 행이 0 으로 세어져 회수 분기에 닿지 않는다 — 더 눈에 덜 띈다) · 중복 제안 방지는 프리필과 같은 코드 경로라 1단계에 함께 넣었다 · 연필 제거로 그 버튼 전용 서버 코드(`updateAnswerForTurn`·`findActiveByTurn`·`findFailedTurnIds`, 엔드포인트 둘)도 지웠다 · `chunkCount()` 를 행 수에서 `SUM(chunk_count)` 로 바꿨다. 압축 전 전문은 커밋 `c9a9ec4` 의 PLAN.md.

**열린 항목**: §10.10 의 열린 항목 중 (b)(문서 삭제 연동)만 남았다 — (a)(큐레이션 축 문턱 별도화)와 (c)(BM25 축 편입)는 해소 · ~~(d) 골든셋 재측정~~ — **2026-10-12 해소**: 검색축을 합친 뒤에도 recall@10 0.962(운영 설정 + 확장 1.000), 회귀 없음([SEARCH_EVALUATION.md](SEARCH_EVALUATION.md)) · 기존 `origin='like'` 활성 행은 마이그레이션하지 않았다(이미 `/admin` 에서 보이고 회수되며, 합쳐진 축의 단일 가중치로 검색된다).

### 10.12 짧은 후속 질문의 독립화 (condense) ✅ 완료 (2026-09-03)

**무엇을 왜.** 검색 축 셋(벡터 · BM25/FTS · 큐레이션)이 전부 **질문 원문**을 보는데 이력은 답변 단계에서만 쓰였다 — `"그 설정은 어디에 있어?"` 는 검색어로 의미가 없고, 사용자에게는 "문서에 있는데 못 찾는다"로 보인다. 하필 그 구간은 MultiQuery 확장도 받지 못했다(`app.search-multiquery-min-length` 미만). `QuestionCondenser` 가 질문을 자립 문장으로 다시 쓰고, 그 한 값을 **검색 축 셋 · 리랭커 · 분류기**가 모두 쓴다(LLM 호출 순증 0). 결정 근거·함정은 PITFALLS [`QuestionCondenser`](PITFALLS.md#servicequestioncondenserjava).

**유지해야 할 결정**: 재작성은 검색에만 쓰고 답변 프롬프트의 `[현재 질문]` 은 **원문**이다(재작성이 빗나가도 검색만 틀리게 하는 격리) · 재료는 이전 **질문**들뿐, 답변은 넣지 않는다(무겁고, 검색이 자기 답변을 다시 찾고, §10.13 이후 이력에 들어가는 Direct 답변의 지어낸 용어가 검색어가 된다) · **두 게이트는 여집합**이고 확장 게이트는 반드시 **원문 길이**로 잰다(재작성 결과로 재면 한 턴에 전처리 호출이 둘이 된다 — "순증 0"의 근거) · 게이트는 길이만 본다(긴 질문의 분류는 즉시 출발해 이력 로딩과의 병렬성이 유지된다) · 실패하면 원문으로 검색한다(재료 없음·원문 반환·호출/파싱 실패가 같은 갈래) · 재작성을 진단 패널에 보이고 `VerificationSnapshot` 에 저장한다(잘못된 재작성은 "엉뚱한 답변"으로만 보이므로 새로고침 뒤에도 남아야 한다) · 출력 상한은 `MAX_OUTPUT_TOKENS`(256) · on/off 키는 `app.search-multiquery-enabled` 를 공유한다(여집합 관계가 스위치까지 공유해야 깨지지 않는다).

**계획이 틀렸던 지점**: 열린 항목 (b)(이전 턴이 Direct 일 때의 오염)는 턴 필터가 아니라 **재료에서 답변을 뺀 것**으로 닫혔다 — 사용자가 쓴 질문에는 모델이 지어낸 용어가 없다 · 분류기 문제는 병렬성과 맞바꾸지 않아도 됐다(게이트가 이력 없이 판단되면 긴 질문은 이미 완료된 future 다). 압축 전 전문은 커밋 `c9a9ec4` 의 PLAN.md.

**열린 항목 — 전부 닫혔다**: ~~(a) 길이 임계값~~ — 2026-10-12 그대로(게이트 10·12·15 비교에서 출하값 15 가 가장 좋거나 같다) · ~~(b)~~ 위 · ~~(c) 실패 폴백~~ 설계대로 원문 검색 · ~~(d) 정확도 실측~~ — **2026-10-13** [SEARCH_EVALUATION.md §3.4](SEARCH_EVALUATION.md)(같은 주제 14 + 주제 전환 7문항): 독립화는 원문보다 1~2건 낫고 주제 전환에서 손해가 없다. 10-12 의 "거의 이득 없음·이어 붙이기가 낫다"는 하네스가 같은 스레드에 앞 질문을 쌓던 결함 위의 결론이라 철회했다 · ~~(e) 게이트 아래에서 무엇을 할 것인가~~ — **2026-10-13 그대로 두기로 결정**(앞 질문 이어 붙이기는 주제 전환에서 무너지고, 원문과 함께 검색은 비기면서 깊은 순위를 잃고, 되풀이 가드는 결과를 바꾼 케이스가 없다). 다시 열 신호: 생성 모델을 바꿀 때(§6.29 열린 항목 (g)와 같은 때) · 실사용에서 재작성이 앞 질문을 되풀이해 엉뚱한 문서가 검색될 때(그때는 되풀이 가드부터).

---

### 10.13 Direct 턴의 이력 확대 — 문서 자리를 이력에 돌려준다 ✅ 완료 (2026-09-03)

**무엇을 왜.** Direct 답변의 프롬프트에는 `[검색된 문서]` 블록이 통째로 없다. 기본 설정에서 그 자리는 `topK 10 × chunk-size 1,500` ≈ 15,000자인데 이력 상한은 모드와 무관하게 고정(당시 5,000자)이라, 정작 이력만으로 답해야 하는 모드가 이력을 가장 적게 받았다. 규칙과 렌더는 `HistoryPolicy` 하나 — 근거·함정은 PITFALLS [`HistoryPolicy`](PITFALLS.md#servicehistorypolicyjava).

**유지해야 할 결정**: 규칙은 "Direct 라서"가 아니라 **"문서 자리가 비어서"** 다(`이력 상한 = 입력 예산 − 문서가 차지할 자리` — 모드별 상수로 두면 `topK` 를 낮춘 배포로의 일반화가 막힌다) · 1차 적용이 Direct 뿐인 이유는 모드가 아니라 **타이밍**이다(이력은 검색보다 먼저 로딩되고 Direct 만 문서 0 이 확정 — 같은 이유로 `meta` 분류는 Direct 로 세지 않는다) · 두 이력 경로가 같은 규칙을 읽는다(갈리면 요약 캐시 TTL 을 기점으로 맥락이 바뀐다) · 렌더는 두 축의 조합(지금 묻는 턴이 *얼마나*, 이전 턴의 모드가 *무엇을* — DN 의 순효과는 캡 제거) · 캡 제거와 예산 인상은 한 커밋(캡만 빼면 N 답변 하나가 이력을 거의 다 먹는다) · 1단계(DN 도 `## 요약` 을 낸다)는 강제하지 않고 조건은 분량이 아니라 **쓸모**다(같은 프롬프트를 meta 가 쓰고, 형식 미준수는 전문 폴백으로 안전하게 수렴한다).

**계획이 틀렸던 지점**: "확대"만이 아니었다 — 좁은 창에서는 고정값보다 **작게** 나오고 그것이 옳다(Direct 경로에 예산 가드가 없어 그 고정값이 이미 창을 넘기고 있었다) · `ResponseModeBranchConventionTest` 는 텍스트 검사라 주석의 금지 패턴에도 빌드가 깨진다 · `trimHistory` 를 `AnswerService` 에서 `HistoryPolicy` 로 옮겼다(Direct 경로도 같은 절단). 압축 전 전문은 커밋 `c9a9ec4` 의 PLAN.md.

**이후 변경 (2026-09-04)**: 이전 턴이 **Direct 였으면** RAG 로 물어도 전문을 싣는다(그 턴에는 복제될 문서가 없었다 — 검증 프롬프트는 이력을 보지 않아 `grounded=false` 가 날 위험은 감수했고, 그때는 재시도가 답변만 다시 쓰고 최종적으로 미검증 배지로 드러난다) · 프롬프트에 싣는 턴 수 상한 `HistoryPolicy.promptTurnCap()`(`app.memory.fetch-limit-turns` 의 절반, 기본 5 — 가져오는 창을 줄이면 요약이 볼 과거까지 준다. 글자 예산과도 독립). 아래 (b)의 전제("폴백 경로는 전문")는 이미 틀려 있었다 — 렌더는 같았고(`DirectHistoryFallbackTest`) 남은 차이는 분량(폴백 10턴 vs 요약 경로 2턴)이라 이 상한이 5 대 2 로 좁혔다.

**열린 항목**: (a) RAG 턴에도 같은 계산을 적용할지 — 로딩 시점엔 `topK × chunk-size` 로 과대 추정할 수밖에 없어 이력이 지금보다 줄 수 있다 · (b) RAG 턴에 대한 두 경로의 분량 차이(위) — 실사용에서 재현성 문제가 보고되면 착수 · (c) 넓어진 사실을 보일지 — 줄어든 쪽만 `budgetNote` 로 말한다(한 스레드에 RAG·Direct 가 섞일 때의 "아까는 기억했는데"는 §10.12 의 진단 패널과 함께 판단).

---

### 10.14 청크 오류 신고 — 사용자 → 관리자 ✅ 완료 (2026-09-04)

> **결정 근거와 틀렸던 지점** → [PITFALLS.md](PITFALLS.md#servicechunkreportservicejava)

**무엇을 왜.** 답변의 출처 배지로 원문(`#chunkFullTextModal`)까지 연 사용자가 **청크의 내용이 틀렸거나 오래됐다는 것을 발견해도 말할 곳이 없었다** — 👍/👎 는 답변 평가라 어느 청크인지 담지 못하고, "현재 대화에서 이 청크 제거"는 청크는 멀쩡한데 이 답변과 무관할 때 쓰는 표시 전용 동작(`turn_source_ref.hidden_at` 만 세운다)이라, 내용이 틀린 청크가 그 대화에서만 조용히 치워지고 다음 사람에게 똑같이 검색됐다. §10.11 이 코퍼스에 **넣는** 문을 관리자 승인 하나로 모았다면, 이것은 **"고치자"의 문**을 같은 모양(사용자 제출 → 관리자 판단)으로 하나 낸다.

**구현 결과 (0~5단계 전부).** `ChunkReportRepository`(`chunk_report`, 운영 DB) · `ChunkReportService` · `ChunkReportController`(`POST /ui/chunk-reports`) · `AdminController` 의 `/admin/chunk-reports/**` 넷과, 채팅 원문 보기 모달의 🚩 버튼 + 신고 폼 · `/admin` 의 청크 단위 대기열 카드/오프캔버스 · 헤더 배지. 결정 근거와 함정은 [PITFALLS.md](PITFALLS.md#servicechunkreportservicejava).

**유지해야 할 결정** (근거와 함정은 PITFALLS): 신고는 **순수 대기열**이다(검색·재사용·벡터/FTS 어디도 바꾸지 않고, 반영은 관리자가 청크를 실제로 고칠 때) · 신고자는 **고칠 수 없다**(사유 4종 `WRONG`·`OUTDATED`·`BROKEN`·`OTHER` + 필수 코멘트 500자) · 관리자는 신고가 아니라 **청크 단위**로 본다(1행 = 청크, 배지도 열린 신고를 가진 청크 수) · 중복 방지 키는 **(청크, 신고자, 대화)** · 사용자당 건수 상한 없음 · "수정됨/삭제됨"은 저장하지 않고 조회 시점에 파생(`chunk_hash` 대조) · 본문·질문 스냅샷은 행마다 · 관리자 조치는 판정만(수정은 기존 편집기) · 신고자 알림은 범위 밖. **두 동작의 경계는 화면의 라벨이 말한다** — *제거* 는 「이 답변과 상관없는 청크」(참여도 0%일 때만 노출), *신고* 는 「내용이 틀렸거나 업데이트가 필요함」(항상 노출). 사유 코드에 "질문과 무관"을 두지 않는 이유다 — 그건 제거 버튼의 몫이고, 넣는 순간 표시 전용 동작이 관리자 대기열로 흘러든다.

**계획대로 되지 않은 지점**: 중복 방지 키가 설계 중에 (청크, 신고자)에서 **(청크, 신고자, 대화)** 로 바뀌었다(`guest-identity=shared` 면 전 방문자가 `GUEST_ID` 하나라 청크당 한 명만 신고할 수 있게 된다) · 0단계는 진짜 버그였다(`fragments/message-assistant.html` 만 `data-chunk-id` 를 빠뜨려 논스트리밍 폴백에서는 출처를 눌러도 모달이 안 열렸다 — `ChatControllerHtmxTest` 가 고정) · `AdminController` 생성자에 서비스를 더하면 기존 WebMvc 테스트 둘의 컨텍스트가 안 뜬다 · "현재 내용"이 백엔드마다 다른 텍스트이고 `vec_document_chunks` 가 없는 배포에서는 쿼리 자체가 예외가 된다(테이블 존재 확인 후 조회).

**열린 항목**

- **(a) 문서 단위 묶음 보기** — 한 문서의 여러 청크에 신고가 흩어지면 "이 문서를 다시 인덱싱해야 한다"가 진짜 조치일 수 있다. 청크별 그룹이 먼저이고, 문서 단위 집계는 실제로 그런 패턴이 보일 때 얹는다.
- **(b) 신고를 검색 품질 지표로 쓸 것인가** — §10.7.5 골든셋과 연결하면 "사람이 표시한 오답 청크"라는 라벨이 생긴다.
- **(c) 개별 신고 반려** — 지금은 조치가 그룹 단위다. 한 청크의 신고 3건 중 하나만 틀린 지적인 경우가 반복되면 행 단위 반려를 얹을지 판단한다(그때도 기본 동선은 그룹이어야 한다).
- ~~(d) 신고자에게 처리 결과 알림~~ — **범위 제외**(PITFALLS 의 ChunkReportService — 읽음 컬럼도 두지 않는다).

---

### 10.15 질문 추천 개선 — Direct 제외 · 모양 복사 · 사전 필터 · 부정 캐시 · 내용어 매칭 ✅ 완료 (2026-09-21)

> **결정 근거와 틀렸던 지점** → [PITFALLS.md](PITFALLS.md#servicequestionreuseservicejava) · [QuestionKeywords](PITFALLS.md#servicequestionkeywordsjava) · [QuestionReuseRepository](PITFALLS.md#repositoryquestionreuserepositoryjava)

**무엇을 왜.** §6.23 의 추천·재사용에 남아 있던 헛클릭과 낭비를 걷어냈다. ① 재사용 턴이 자리표시자(`'M'`·direct 0·빈 태그)로 저장돼 표기·프리필·이력 렌더가 원본과 어긋났다 → 원본 턴의 모양을 복사. ② 좋아요한 Direct 턴이 후보에 올랐지만 근거 청크가 없어 검증을 통과한 적이 없었다 → Direct 는 후보에서 제외(좋아요는 지식 제안 신호일 뿐). ③ 검증에 떨어질 것이 확정된 행이 `LIMIT` 창을 차지하고 키 입력마다 행당 쿼리 둘로 재확인됐다 → 활성 출처가 없는 턴은 SQL 이 미리 빼고(`HAS_ACTIVE_SOURCE_PREDICATE`), 해시 대조에서만 드러나는 실패는 10분 부정 캐시. ④ 입력 문장 통째 부분 일치라 어순·조사가 조금만 달라도 못 찾았다 → `QuestionKeywords` 가 의문·기능어·조사·어미를 걷어낸 내용어(최대 6개)를 뽑고 전부(AND) 부분 일치. 상한 12 → 20.

**앞으로도 지켜야 할 결정**

- **접미사 절단은 언제나 접두사를 남긴다** — 부분 일치에서 접두사는 재현율을 깎지 않는다. 한 글자 조사는 2음절 어간이 남을 때만(`경로·결과·추가·정의` 보호), 어미 뒤에 한 음절만 남으면 토큰을 버린다(AND 에서 노이즈 키워드는 후보를 떨어뜨린다).
- **부정 캐시는 TTL 이지 영구 표시가 아니다** — 큐레이션 청크 id 는 결정적이라 재승인이면 같은 해시가 돌아온다. 영구 표시는 통지 경로의 몫.
- **사전 필터는 `validateTurn()` 보다 엄격하지 않아야 하고 재사용 조회에는 걸지 않는다** — 클릭 시 사유 문구는 검증이 낸다.
- **범위는 `shared` 고정이며 HTTP 로 받지 않는다**(2026-09-23 보강) — `suggest`/`reuse` 가 `scope` 파라미터를 선언해 놓고 값을 읽지 않아 `?scope=me` 가 동작한다고 믿게 했다. 받되 읽지 않는 손잡이는 없느니만 못하다. "내 질문만"이 성립하려면 화면 토글과 함께 기본 배포(`guest-identity=shared`)의 공유 게스트 id 부터 풀어야 한다 — 방문자 전원이 같은 `user_id` 라 그 필터가 아무것도 걸러내지 못한다. 출처 구분은 필터가 아니라 항목마다 붙는 `origin` 이 한다.

**남은 열린 항목**

- AND 매칭이라 키워드 하나가 저장 질문에 없으면 못 찾는다(용언 활용 `바꾸`↔`변경`, 동의어). "3개 이상이면 하나까지 빠져도 허용" 같은 완화는 실사용 관찰 뒤.
- 불용어·조사·어미 목록은 `QuestionKeywords` 상수 하나다 — 빠지거나 과한 단어는 거기서만 고친다.
- ~~서비스·리포지토리의 `Scope.ME`/`meOnly` 축~~ — **2026-10-12 걷어냄**: enum, `suggest`/`reuseLookup` 의 범위 인자, 리포지토리 두 조회의 `meOnly` 분기와 `userId` 인자(재사용 조회), 추천 응답의 `scope` 필드(항상 `"shared"`, 화면·문서 어디도 읽지 않았다). 되살리려면 위 결정대로 화면 토글과 공유 게스트 id 부터 풀어야 한다.

---

## 11. 리스크 및 이슈

| 리스크 | 심각도 | 완화 방안 |
|--------|--------|----------|
| BCrypt 동시 해시로 CPU 포화 | 중 | cost=10~12 사이 부하 테스트 후 결정. Virtual Thread는 CPU-bound 작업엔 도움 안 됨에 주의 |
| SQLite write 락 — 로그인 실패 카운트 UPDATE와 채팅 INSERT 충돌 | 중 | `busy_timeout=5000` 설정. 잦은 쓰기 테이블은 별도 SQLite 파일로 분리 검토 |
| HTMX + CSRF 헤더 누락으로 모든 요청 403 | **고** | `htmx:configRequest` 전역 핸들러로 자동 주입. `chat-stream.js` fetch에도 적용 누락 없는지 e2e 점검 |
| IDOR — 타 사용자 `threadId`/`docId` 접근 | **고** | Repository 시그니처에 userId 강제. Controller 진입 시점에 소유권 검증 단위 테스트 추가 |
| Chroma 컬렉션명 길이 제한 (63자) | 중 | UUID 앞 8자 + version만 사용. 충돌 방지 위해 `users.id`를 UUIDv7로 발급 |
| Flyway SQLite 모듈 호환성 | 중 | Flyway 10.x 기준 `flyway-database-sqlite` 별도 의존성 필요. PoC 먼저 수행 |
| 세션 쿠키 도난 — XSS | **고** | CSP 엄격 적용, HttpOnly+Secure+SameSite=Lax, DOMPurify 유지 |
| 업로드 파일 path traversal | **고** | 저장 경로 `Path.normalize().startsWith(userBase)` 검증 |
| 모바일 키보드 가림 | 중 | `100dvh` 단위로 레이아웃 고정(`app.css`). ⚠️ `visualViewport` API 기반 입력창 위치 보정은 **미구현** — 소프트 키보드 출현 시 하단 고정 여부는 실기기 검증 필요(상단 대시보드 "Phase 2 남은 실기기 검증 2건" 참조) |
| HTTPS 인증서 갱신 실패 | 중 | Caddy 자동 갱신 + 만료 30일 전 헬스체크 알림 |
| sqlite-vec — SQLite pool=1과 vec0 쓰기 충돌 | **고** | 기존 `busy_timeout=5000` 유효. vec0도 WAL 모드 하에서 동작하나 대규모 add() 시 write 홀딩 시간 측정 필요 |
| sqlite-vec — 차원수 불일치 | **고** | `vec_embeddings` 테이블 생성 시 `app.embedding.dimensions` 값으로 DDL 생성. 임베딩 모델 변경 시 DROP+재인덱싱 필수 — 자동 감지 불가 |
| sqlite-vec — 네이티브 바이너리 운영자 제공 | 중 | 공식 Java 아티팩트가 없어 운영자가 플랫폼별 `vec0` loadable을 배치하고 `SQLITE_VEC_EXTENSION_PATH`로 지정. Docker는 컨테이너 아키텍처(`linux/amd64` 등)에 맞는 바이너리 사용. 미설정/플랫폼 불일치 시 `SqliteVecVerifier`가 기동 시 fail-fast |
| sqlite-vec — searchBatch() N회 JDBC 쿼리 성능 | 낮 | 임베딩 배치 생성(1 HTTP 호출)이 병목. JDBC 쿼리 N번은 인메모리 SQLite 특성상 수 ms 수준으로 예상. 실측 후 CTE 방식 전환 검토 |
| sqlite-vec — Spring AI VectorStore 미지원 | 중 | 커스텀 `VectorStoreProvider` 구현으로 Spring AI 인터페이스 우회. Spring AI 공식 sqlite-vec 지원 시 마이그레이션 경로 단순화 |
| 태그 엄격 필터 — 결과 과소(recall 저하) | 중 | sqlite-vec 후보확대 보정(`candidateK` 단계 확대), AND 고정 1차 출시 후 OR 모드 후속 도입 |
| 프리릴리즈 수동 초기화 — 데이터 유실 위험 | 중 | 적용 전 운영자 체크리스트(백업 선택), 초기화 대상 경로 명시, 재업로드/동기화 검증 절차 문서화 |

---

## 11.1 예정 — Spring AI 2.0 업그레이드 & Chroma 버전 정책 🔜 미착수

### 배경 (현황 정정)

**Chroma v2 API는 이미 적용된 상태다.** Spring AI 1.1.8의 `ChromaApi`는 v2 경로만 호출하므로 이 앱은 **v1 전용 Chroma 서버(0.5.x 이하)에서는 동작하지 않는다** — "앞으로 v2로 올린다"가 아니라 "이미 v2만 쓴다"가 정확한 현황이다. 남아 있던 v1 잔재는 코드가 아니라 배포 설정이었고 둘 다 조치 완료: 헬스체크 경로 `/api/v1/heartbeat`→`/api/v2/heartbeat`(404로 컨테이너가 영영 `unhealthy`가 되어 `depends_on: service_healthy`가 통과되지 않던 문제), 이미지 태그 `:latest`→`1.0.21` 고정(README 예시 3곳 포함).

### 예정 작업

| 항목 | 내용 | 상태 |
|------|------|------|
| Chroma 태그 고정 | `:latest` → `1.0.21`, 헬스체크 v2 경로 | ✅ 완료 |
| Chroma 1.1.x 검증 | Docker Hub에 `1.1.0` 계열 존재. `ChromaApi`가 쓰는 v2 엔드포인트 스키마가 그대로인지 실기동 확인 후 태그 상향 | 🔜 |
| Spring AI 2.0 업그레이드 | `spring-ai.version` 1.1.8 → 2.0.x. 로컬 m2에 `spring-ai-chroma-store:2.0.0`이 이미 받아져 있어 API 차이 비교는 즉시 가능 | 🔜 |

### Spring AI 2.0 업그레이드 시 점검 포인트

이 프로젝트는 Spring AI의 자동설정을 대부분 **끄고** 직접 빈을 만들기 때문에, 업그레이드 리스크가 일반적인 경우와 다르다:

1. **`spring.autoconfigure.exclude` 클래스명 7종** — Chroma 1종 + OpenAI 모델 6종의 FQCN이 2.0에서 바뀌면 exclude가 조용히 무효가 되고, `LOCAL_LLM_KEY`가 빈 로컬 구성에서 `OpenAI API key must be set`로 기동이 깨진다(현재도 이 실패 클래스를 막으려고 넣은 설정).
2. **`ChromaApi`/`ChromaVectorStore` 빌더 시그니처** — `ChromaConfig`·`VectorStoreRegistry`·`ChromaVectorStoreProvider`가 직접 호출한다. 특히 `upsertEmbeddings()`(§10.1 embed≠store 분리를 위해 `VectorStore.add()`를 우회하는 지점)와 `QueryRequest.Include` 필드 선택.
3. **`OpenAiApi.builder()` / `OpenAiChatOptions`** — `LlmConfig`가 프로바이더마다 직접 조립. `ChatCompletionRequest` 직접 조립(`DirectAnswerService`의 스트리밍 우회 경로)도 동일.
4. **`MultiQueryExpander` / `spring-ai-rag`** — 프롬프트 템플릿 주입 방식(`promptTemplate`, `numberOfQueries`, `includeOriginal`) 변경 여부.
5. **`EmbeddingModel` 데코레이터 체인** — `Tracking`/`Caching`/`LoadBalancing` 3중 래핑이 인터페이스 변경에 직접 노출된다.
6. **`TokenCountBatchingStrategy`** — Chroma 경로의 서브배치 분할에 사용.

> 업그레이드는 별도 브랜치에서 `mvn -o test` 전체 통과 + Chroma/sqlite-vec 양쪽 실기동 확인을 완료 조건으로 한다(테스트 수는 적지 않는다 — 계속 늘어 숫자를 옮겨 적으면 낡기만 한다).

---

## 12. 의존성 변경 사항 (pom.xml)

> **압축**: 완료된 Phase의 의존성 이력·설정 덤프는 **pom.xml/application.properties 자체가 단일 출처**라 여기 중복 유지는 드리프트 위험만 키운다. 코드에서 바로 확인 안 되는, 실제로 유용한 사실만 남긴다.

- **`flyway-database-sqlite`는 불필요로 판명** — SQLite 마이그레이션 지원은 `flyway-core`에 이미 내장되어 있다. 다시 검토할 필요 없음.
- **`app.security.*` 외부화는 미착수** — BCrypt cost(12)는 `SecurityConfig.java`에, 로그인 잠금(5회/15분)은 `AuthEventListener.java`(`MAX_ATTEMPTS`/`LOCK_MINUTES`)에 상수로 하드코딩되어 있다. 외부화 필요 시 `AppProperties`에 `SecurityConfig` record + `securitySafe()` 가드를 추가하고 이 두 지점을 프로퍼티 참조로 교체.
- 그 외 전체 의존성/프로퍼티 목록은 pom.xml·application.properties 참조.

---

## 13. DB 스키마 변경 요약

> ⚠️ **분리 배포에서 Flyway는 실데이터에 닿지 않았다** (2026-09-04 확인 → 2026-09-28 해소): `SQLITE_VEC_DB_PATH` 를 설정하면 운영 테이블까지 벡터 DB 파일에 만들어졌고(원인은 [PITFALLS](PITFALLS.md#벡터-스토어-백엔드와-vecfts-datasource) 의 `JdbcTemplate` 자동설정 백오프), Flyway 는 `@Primary` DataSource(`memory.db`)에만 적용됐다. **Step 5.10 환원으로 DB 파일이 하나가 되어 Flyway 는 다시 실데이터 파일에 적용된다.** 이력 없이 런타임 DDL 로 만들어진 옛 벡터 DB 파일은 `spring.flyway.baseline-version=3` 으로 시작한다 — 1 이면 V2 의 `CREATE TABLE users`(`IF NOT EXISTS` 없음)가 `already exists` 로 기동을 멈춘다(`FlywayBaselineTest` 가 실제 설정값으로 고정).
>
> **Flyway 일원화 (2026-09-29, V4)**: 그때까지 Flyway 는 V1–V3 로 네 테이블의 첫 모양만 만들고, 나머지 테이블과 이후 컬럼은 저장소 11개 클래스의 런타임 멱등 DDL(`CREATE TABLE IF NOT EXISTS` + 방어적 `ALTER TABLE ADD COLUMN`)이 만들었다 — 스키마가 두 곳에 있었고 같은 테이블을 두 곳이 다른 모양으로 선언했다. `db.migration.V4__Consolidate_runtime_schema`(Java 마이그레이션 — SQLite 에 `ADD COLUMN IF NOT EXISTS` 가 없어 여러 옛 상태를 SQL 하나로 수렴시킬 수 없다)가 테이블·컬럼 존재를 확인하며 빠진 것만 만들고, 좋아요 시절 `curated_qa`(`source_turn_id NOT NULL`)의 재생성도 옮겨 왔다. 저장소의 런타임 DDL 은 전부 제거했고, 저장소 테스트는 `SqliteTestDatabase`(앱과 같은 Flyway 설정으로 만든 템플릿 DB 복사)로 전환했다. 옛 DDL 은 `src/test/resources/db/pre-v4-runtime-ddl.sql` 에 기록으로 남아 `FlywaySchemaConvergenceTest`(옛 새 설치·이력 없는 옛 벡터 DB·최근 컬럼이 빠진 옛 버전·좋아요 시절 `curated_qa` → 전부 새 설치와 같은 스키마, 그리고 V1–V4 의 결과 = 옛 DDL 의 결과)의 재료가 된다.
>
> **신규 컬럼 추가 지침**: 새 테이블·컬럼·인덱스는 **`V5` 이후의 SQL 마이그레이션 파일**(`src/main/resources/db/migration/`)로 추가한다 — V4 뒤로는 모든 DB 가 한 모양이라 평범한 `ALTER TABLE ... ADD COLUMN` 한 줄이면 된다(재실행 방어 불필요). 저장소 클래스에 DDL 을 다시 넣지 않는다. 예외는 SQL 이 알 수 없는 값이 필요한 **데이터 이관**뿐이다 — `V9__Local_timestamps_to_utc` 는 JVM 시간대가 필요해 Java 다(스키마 변경 없음). 적용된 V1–V9 의 목록과 내용은 [DATABASE.md](DATABASE.md) 의 스키마 관리 절. V4 는 적용된 마이그레이션이므로 고치지 않는다(Java 마이그레이션은 체크섬이 없어 Flyway 가 수정을 모른다 — 위 테스트가 대신 잡는다). 예외: 다시 만들 수 있는 검색 색인(`chunk_fts`·`chunk_fts_key` 는 `KeywordSearchRepository`, `vec_embeddings`/`vec_document_chunks` 는 `SqliteVecSchemaInitializer` — 차원 파라미터화·vec0 확장 필요)은 계속 그 컴포넌트의 동적 DDL 로 관리된다.
>
> **테이블·컬럼·메타데이터 키를 추가하면 [DATABASE.md](DATABASE.md)의 해당 표도 함께 고친다** (2026-09-29 신설 — 테이블별 컬럼·인덱스·관계와 벡터 저장 구조 레퍼런스).

---

## 부록 — 결정 사항 한눈에 보기

| 주제 | 결정 | 대안 (보류) |
|------|------|-----------|
| 인증 방식 | HttpSession + Spring Security 폼 로그인 | JWT (다중 서버 시 도입) |
| 비밀번호 해시 | BCrypt cost=12 | Argon2 (CPU/메모리 변수 더 많음, 부하 테스트 후 결정) |
| TLS 종료 | Caddy 리버스 프록시 | Spring 직접 TLS, Cloudflare |
| DB | SQLite 유지 (WAL+busy_timeout) | PostgreSQL (한계 신호 발생 시) |
| SQLite 파일 구성 | 단일 파일 `memory.db` (2026-09-28, Step 5.10 환원) | 운영/벡터 파일 분리 — Step 5.10 이 시도했으나 배선 사고로 실효된 적 없음. 재개 신호: 대량 인덱싱 중 채팅 지연이 **실측**될 때. 선행: `QuestionReuseRepository.findSourcePreviewRows()` 교차 조인 해소(ATTACH/2단계 조회) + 기존 배포 운영 테이블 이전 |
| 마이그레이션 | Flyway + ANSI SQL | Liquibase |
| 운영 테이블 스키마 | Flyway 일원화 (2026-09-29) — V4(Java)가 옛 런타임 DDL 상태들을 한 모양으로 모으고, 이후 변경은 V5+ SQL 파일 (§13) | 저장소별 런타임 멱등 DDL(`CREATE TABLE IF NOT EXISTS` + 방어적 `ALTER`) — 스키마가 V1–V3 와 저장소 11곳에 흩어지고 저장소 테스트가 손으로 만든 축소 스키마로 돌았다 |
| 멀티테넌시 | Row-level + 사용자별 Chroma 컬렉션 | Schema/DB per tenant |
| 세션 저장소 | 인메모리 (단일 인스턴스) | Spring Session + Redis (스케일아웃 시) |
| CSRF | Spring Security 기본 + HTMX 자동 주입 | Double-submit cookie |
| Rate limit | Bucket4j 인메모리 | Redis 백엔드 (스케일아웃 시) |
| 모바일 | HTMX + Bootstrap Offcanvas + PWA | React Native 네이티브 앱 |
| 소셜 로그인 | Phase 4 (필요해질 때) | Phase 1 동시 — 복잡도 ↑ |
| Vector Store | `app.vectorstore.type` 런타임 선택 (기본 `chroma`) | Spring AI 공식 sqlite-vec 지원 시 단순화 가능 |
| sqlite-vec 스키마 | 앱 시작 시 동적 DDL (`IF NOT EXISTS`) — 차원수 파라미터화 | Flyway 마이그레이션 — vec0 DDL에 차원 하드코딩 필요해 부적합 |
| sqlite-vec searchBatch | 임베딩 배치(1 HTTP) + JDBC 루프(N 쿼리) | 단일 CTE 쿼리 — vec0 API 제약으로 현재 불가 |
