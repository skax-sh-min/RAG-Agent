# RAG Agent — Spring AI / Java 21

Spring AI + Spring Boot 3.5 + Java 21 기반의 문서 기반 지식 Q&A 에이전트입니다.  
REST API와 Thymeleaf + HTMX 기반 Web UI를 모두 제공합니다.

## 실행 방법

### Docker Compose (권장)

```bash
cp .env.example .env   # 환경변수 설정
docker-compose up --build
```

### 로컬 빌드

```bash
# git 훅 설치 (클론 후 1회 실행)
sh scripts/install-hooks.sh

# 테스트 포함 빌드
mvn clean package

# 테스트 생략 빌드 (빠름)
mvn clean package -DskipTests

# Exploded 빌드 — fat JAR로 묶지 않고 계층화된 디렉터리로 추출
mvn clean package -DskipTests
java -Djarmode=tools -jar target/rag-agent-*.jar extract --destination target/extracted
```

빌드 완료 후 `target/rag-agent-*.jar` 파일이 생성됩니다.

> **Exploded 실행** — 위 `extract` 단계 완료 후, 풀어진 레이아웃에서 바로 실행합니다. JVM이 런타임에 JAR을 해제할 필요가 없어 기동이 빠릅니다:
> ```bash
> java -jar target/extracted/rag-agent-*.jar
> ```
> `--destination target/extracted`는 최초 1회만 실행하면 되며, 이후에는 `java -jar` 명령만 사용합니다.

### 로컬 실행

> **벡터 스토어 백엔드** — 기본은 ChromaDB. `VECTORSTORE_TYPE=sqlite-vec`로 설정하면 벡터를 SQLite 파일에 저장하고 아래 **"Chroma 서버" 단계를 생략**할 수 있습니다 (운영자가 제공하는 `vec0` 네이티브 확장 필요 — [OPERATOR_MANUAL.md](documents/OPERATOR_MANUAL.md) 참조). 인터넷·Docker 없이 sqlite-vec + 로컬 llama-server로만 돌리는 폐쇄망 구성은 [OPERATOR_MANUAL.md §4.5](documents/OPERATOR_MANUAL.md#45-폐쇄망air-gapped--노-도커-실행) 참조.

> **Chroma 버전 — v2 API 필수.** Spring AI 1.1.8의 `ChromaApi`는 `/api/v2/tenants/{tenant}/databases/{database}/…` 경로만 호출하는데 tenant/database 개념은 Chroma v1 API에 존재하지 않으므로, **v1 시절 서버(0.5.x 이하)와는 호환되지 않습니다**. 아래 명령과 `docker-compose.yml`은 `:latest` 대신 `chromadb/chroma:1.0.21`로 태그를 고정합니다 — Chroma는 메이저 업그레이드에서 HTTP API를 바꾼 전례가 있어 `:latest`는 어느 날 조용히 앱을 깨뜨릴 수 있습니다. 버전을 올릴 땐 의도적으로 이 태그를 바꾸세요.

#### 개발 모드 (소스 직접 실행)

```bash
# 1. Chroma 서버 (별도 터미널)
docker run --rm -p 8001:8000 \
  -v "$(pwd)/data/chroma:/data" \
  chromadb/chroma:1.0.21

# 2. 환경변수 설정
cp .env.example .env

# 3. 애플리케이션 실행
mvn spring-boot:run
```

#### JAR 실행 (빌드 후)

```bash
# 1. Chroma 서버 (별도 터미널)
docker run --rm -p 8001:8000 \
  -v "$(pwd)/data/chroma:/data" \
  chromadb/chroma:1.0.21

# 2. 환경변수 로드 후 JAR 실행
export $(grep -v '^#' .env | xargs)
java -jar target/rag-agent-*.jar
```

#### macOS — Apple Container (Apple Silicon 대안)

```bash
# 0. 설치 (최초 1회): https://github.com/apple/container/releases 에서 .pkg 다운로드

# 1. 컨테이너 시스템 시작 (설치 후 또는 재부팅 후 1회)
container system start

# 2. Chroma 시작 (별도 터미널)
container run --rm -p 8001:8000 \
  -v "$(pwd)/data/chroma:/data" \
  chromadb/chroma:1.0.21

# 3. 환경변수 로드 후 실행
export $(grep -v '^#' .env | xargs)
mvn spring-boot:run

# 종료
container stop <CONTAINER_ID>
container system stop
```

> 편의 스크립트 `scripts/macos_run_by_apple_container.sh`를 사용하면 위 단계를 자동으로 수행합니다.

접속: http://localhost:8080

자세한 사용법은 [USER_MANUAL.md](documents/USER_MANUAL.md)를, 배포·LLM 설정은 [OPERATOR_MANUAL.md](documents/OPERATOR_MANUAL.md)를 참고하세요.

## 환경 변수

### 연결 / 인증

| 변수 | 필수 | 기본값 | 설명 |
|------|------|--------|------|
| `SERVER_PORT` | — | `8080` | 애플리케이션이 리스닝할 포트. 다른 로컬 서비스와 충돌할 때만 변경 |
| `LOCAL_LLM_URL` | 이 provider 사용 시 ✅ | 없음 | `providers[1]`(`local`) 엔드포인트 (임베딩 폴백으로도 사용, 아래 G3 검증 대상 아님). **미설정·공백이면 이 provider가 통째로 비활성화됨** — 더 이상 `http://localhost:1234/v1`로 조용히 폴백하지 않음. 값을 설정하면 기동 시 `GET {URL}/models`로 접속 가능·모델명 일치 여부를 검증하며, 실패하면 **애플리케이션이 시작되지 않는다**(G3, [OPERATOR_MANUAL.md §5.2](documents/OPERATOR_MANUAL.md#52-프로바이더-속성) 참고) |
| `LOCAL_LLM_KEY` | — | `no-key` | `providers[1]` API 키. **로컬 엔드포인트(llama-server)는 키 불필요** — `LOCAL_LLM_URL`만 설정돼 있다면 키가 비어도 LOCAL provider는 등록됨(`no-key` 치환) |
| `LOCAL_LLM_MODEL` | — | `google/gemma-4-e4b` | `providers[1]` 모델명 |
| `LOCAL_LLM_TYPE` | — | `BOTH` | `providers[1]` 작업 유형(`app.llm.providers[1].type`): `MICRO_TEXT`/`LIGHT_TEXT`/`TEXT`/`VISION`/`LIGHT_BOTH`/`BOTH`. 기본 `BOTH`(전 작업 처리); 예: 로컬 모델을 채팅 텍스트 전용으로 한정하려면 `TEXT` |
| `LOCAL_LLM_URL_2` | 사용 시 ✅ | 없음 | `providers[2]`(`local-2`) 엔드포인트 — `providers[1]`(`local`)과 동일 role·priority로 등록되는 두 번째 로컬 LLM 인스턴스로, 요청이 둘 사이에 least-in-flight로 로드밸런싱됨 — [OPERATOR_MANUAL.md §5.4 예제 5/7](documents/OPERATOR_MANUAL.md) 참고. **미설정·공백이면 이 provider가 통째로 비활성화됨**(회귀 0 — 2대째가 없으면 그냥 비워두면 `local` 단독으로 동작). **값을 설정하면 기동 시 검증하며(G3) 실패 시 애플리케이션이 시작되지 않는다** — "설정은 했지만 서버가 아직 안 떠 있다"가 예전처럼 런타임 폴백으로 넘어가려면 `LLM_VERIFY_LOCAL_MODELS_ON_STARTUP=false`가 필요함 |
| `LOCAL_LLM_KEY_2` | — | `no-key` | `providers[2]` API 키 (로컬 엔드포인트는 무시 — 비우면 `no-key` 치환, `LOCAL_LLM_KEY`를 상속하지 않음) |
| `LOCAL_LLM_MODEL_2` | — | `LOCAL_LLM_MODEL` 폴백 | `providers[2]` 모델명 — 보통 `providers[1]`과 동일 모델을 다른 서버에 복제 |
| `LOCAL_LLM_TYPE_2` | — | `BOTH` | `providers[2]` 작업 유형(`app.llm.providers[2].type`). 값 집합은 `LOCAL_LLM_TYPE`과 동일. 보통 `BOTH` |
| `LOCAL_FAST_LLM_URL` | 사용 시 ✅ | 없음 | §6.21 태스크별 모델 오프로딩 — `providers[0]`(`local-fast`) 엔드포인트. **미설정·공백이면 이 provider가 통째로 비활성화됨** — `MICRO_TEXT`는 `local`이 흡수하되, **대화 요약만은 흡수하지 않고 생략**(채팅은 원본 history 폴백). **값을 설정하면 기동 시 검증하며(G3) 실패 시 애플리케이션이 시작되지 않는다** — [OPERATOR_MANUAL.md §5.4 예제 6](documents/OPERATOR_MANUAL.md) 참고 |
| `LOCAL_FAST_LLM_KEY` | — | — | `providers[0]` API 키. `LOCAL_LLM_KEY`와 마찬가지로 로컬 엔드포인트는 보통 불필요 |
| `LOCAL_FAST_LLM_MODEL` | — | `Qwen3.5-0.8B-Q4_K_M.gguf` | `providers[0]` 모델명 |
| `LLM_VERIFY_LOCAL_MODELS_ON_STARTUP` | — | `true` | (`app.llm.verify-local-models-on-startup`) — G3 토글. `true`면 URL이 설정된 각 LOCAL provider에 대해 기동 시 `GET {URL}/models`를 호출해 접속 가능·모델명 일치를 확인하고, 실패하면 애플리케이션이 시작되지 않는다. 로컬 서버가 앱보다 늦게 뜨는 배포 순서 레이스가 있을 때만 `false`로 끌 것 — 그 경우 예전처럼 첫 요청 실패 후 런타임 폴백된다 |
| `LLM_ROUTING_MODE` | — | `COST_FIRST` | 기본 라우팅 모드 (`app.llm.default-routing-mode`). 폐쇄망/로컬 전용은 `LOCAL_ONLY`로 외부 프로바이더 호출 차단 — `LOCAL_ONLY`로 설정하면 채팅 사이드바의 라우팅 전략 드롭다운 자체가 사라짐(어떤 모드를 골라도 결과가 같으므로) |
| `LLM_DEFAULT_PROVIDER_CONCURRENCY` | — | `3` | 질의 경로 프로바이더별 동시성 게이트(`app.llm.default-provider-concurrency`) — 앱이 한 프로바이더에 보내는 동시 요청이 이 값을 절대 넘지 않음(LLM 서버의 실제 `--parallel` 값에 맞춤). 프로바이더별 오버라이드: `app.llm.providers[N].concurrency` |
| `LLM_PERMIT_WAIT_TIMEOUT_SECONDS` | — | `60` | 동시성 슬롯 대기 상한(`app.llm.permit-wait-timeout-seconds`) — 초과 시 read timeout까지 기다리지 않고 즉시 HTTP 429 + `Retry-After` 응답. 인덱싱/백그라운드 LLM 호출에는 적용되지 않음 |
| `LLM_TEMPERATURE` | — | `0.0` | 일반/RAG 답변 temperature(`app.llm.temperature`) — 빈 생성 시점에 각 프로바이더의 `OpenAiChatOptions`에 고정됨. `/settings`에서 **조회 전용**(변경하려면 재기동) |
| `LLM_MAX_TOKENS` | — | `6000` | **블로킹** LLM 호출(분류·키워드 추출·MD 교정·충분도/근거 평가·TXT 구조화 등) 전용 completion 길이 상한 — 스트리밍 채팅/Direct 답변은 이 값과 무관(대신 SSE 타임아웃이 제한). 대화 히스토리 예산·MD 교정 섹션 분할 크기도 같은 값을 공유. **모델 컨텍스트 윈도우 자체가 아님** — 실제 LLM 서버 컨텍스트 크기에 여유를 두고 설정할 것 — [PIPELINE.md §4.1](documents/PIPELINE.md#41-appllmmax-tokensllm_max_tokens-크기-산정--로컬-llm-컨텍스트-윈도우와의-관계) 참고 |
| `DIRECT_LLM_TEMPERATURE` | — | `0.1` | meta/Direct 답변 전용 temperature(`app.llm.direct-temperature`), `LLM_TEMPERATURE`와 별개, `[0.0, 0.2]`로 clamp. **`/settings`에서 핫 수정** — 재기동 없이 다음 Direct 호출부터 적용 |
| `OPENAI_API_KEY` | — | — | OpenAI providers 사용 시 필요. 미설정 시 해당 providers 자동 비활성화 |
| `GEMINI_API_KEY1` / `GEMINI_API_KEY2` | — | — | Gemini providers 사용 시 필요(NORMAL/PREMIUM 쌍마다 키 1개 — [OPERATOR_MANUAL.md §5](documents/OPERATOR_MANUAL.md#5-llm-프로바이더-설정) 참고). 미설정 시 해당 providers 자동 비활성화 |
| `GEMINI_MODEL` | — | provider별 상이 | Gemini NORMAL 티어 두 provider(`providers[3]` gemini-flash-lite, `providers[4]` gemini-flash) 모델명 오버라이드. ⚠ 두 provider가 같은 변수를 참조하므로 설정 시 둘이 같은 모델로 합쳐짐 — 각자 기본값을 유지하려면 비워둘 것 |
| `EMBED_BASE_URL` | — | `LOCAL_LLM_URL` | 임베딩 전용 엔드포인트. 미설정 시 `LOCAL_LLM_URL` 사용 |
| `EMBED_API_KEY` | — | `LOCAL_LLM_KEY` | 임베딩 API 키. 미설정 시 `LOCAL_LLM_KEY` 사용 |
| `EMBED_MODEL` | — | `text-embedding-nomic-embed-text-v1.5` | 임베딩 모델명 |
| `EMBED_DIMENSIONS` | sqlite-vec 시 | — | 임베딩 모델의 실제 출력 차원 (`app.embedding.dimensions`). `sqlite-vec` 필수 (vec0 DDL에 고정 — 모델 실제 차원과 일치: nomic=768, bge-m3=1024). chroma는 무시 |
| `EMBED_USAGE_FALLBACK_ENABLED` | — | `true` | 임베딩 서버가 토큰 사용량을 반환하지 않을 때 `/llm-usage` 대시보드에 0 대신 입력 텍스트 길이 근사(chars/4)로 기록 |
| `EMBED_MAX_CHUNK_CHARS` | — | `0` (비활성) | 임베딩 서버 배치/토큰 한계에 맞추는 청크 문자 수 하드 상한. `input (N tokens) is too large ... (batch size: 512)` 에러 시 설정(예: `450`). 초과 청크는 줄 경계에서 강제 재분할. 먼저 서버 배치를 키우는 것(`llama-server -b/-ub`)을 권장 — [OPERATOR_MANUAL §8](documents/OPERATOR_MANUAL.md#8-문제-해결) 참조 |
| `EMBED_ADDITIONAL_BASE_URLS` | — | — | §6.21 E1 — 추가 임베딩 엔드포인트(동일 모델·차원, 예: N개 GPU 복제본), 콤마 구분. 설정 시 `EMBED_BASE_URL`+이 목록에 걸쳐 least-in-flight 로드밸런싱 — [OPERATOR_MANUAL §3.2](documents/OPERATOR_MANUAL.md) 참조 |
| `EMBED_MAX_CONCURRENT_BATCHES` | — | `1` | §6.21 E2 — 단일 문서 인덱싱 시 서브배치 병렬 임베딩 수(`1`=직렬, 기본 → 회귀 0). 대략 (엔드포인트 수 × 엔드포인트별 병렬)로 설정해 단일 대용량 파일이 E1 엔드포인트를 채우게 |
| `VECTORSTORE_TYPE` | — | `chroma` | 벡터 스토어 백엔드 — `chroma` 또는 `sqlite-vec` |
| `SQLITE_VEC_EXTENSION_PATH` | — | — | sqlite-vec 전용 — 운영자가 제공하는 `vec0` 로더블 확장 경로 |
| `CHROMA_HOST` | — | `http://localhost` | Chroma 서버 호스트 (chroma 백엔드) |
| `CHROMA_PORT` | — | `8001` | Chroma 서버 포트 (chroma 백엔드) |
| `DATA_DIR` | — | `./data` | 문서·레지스트리·SQLite DB 저장 경로 |

### 이미지 처리 / 속도 제한 / 감사 로그

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `IMAGE_DESCRIPTION_ENABLED` | `true` | `LazyVisionService` on/off (`app.image-description.enabled`). `@ConditionalOnProperty` 빈 게이트라 **재기동 필요**. `false`면 이미지 마커만 저장하고 검색 시 Vision 호출 없음 |
| `IMAGE_OCR_ENABLED` | `true` | 스캔 PDF 페이지의 Tesseract OCR (`OcrService`, 동일한 구조적 빈 게이트) |
| `IMAGE_OCR_TESSDATA_PATH` | (빈 값) | Tesseract `tessdata` 디렉터리 절대경로. 비우면 `TESSDATA_PREFIX` 환경변수 → 시스템 기본 경로 순으로 탐색 |
| `IMAGE_CLASSIFY_TYPE` | `true` | 설명 생성 전 이미지 유형(다이어그램/스크린샷/차트/사진) 분류 후 유형별 Vision 프롬프트 선택 |
| `DOCX_EMF_CONVERT` | `true` | DOCX EMF 벡터 이미지를 Batik으로 PNG 변환 (추가 설치 불필요) |
| `DOCX_WMF_CONVERT` | `false` | DOCX WMF 이미지를 LibreOffice headless로 변환 (`soffice`가 PATH에 있어야 해서 기본 off). 끄면 해당 이미지는 `[이미지(변환불가): …]` 마커로 남음 |
| `RATE_LIMIT_ENABLED` | `true` | 사용자별 토큰 버킷 전체 스위치 (`app.rate-limit.*`) |
| `RATE_LIMIT_CHAT_PER_MINUTE` | `60` | 사용자당 `/chat` 분당 요청 수 |
| `RATE_LIMIT_UPLOAD_PER_MINUTE` | `10` | 문서 업로드 분당 요청 수 |
| `RATE_LIMIT_SYNC_PER_MINUTE` | `3` | 폴더 동기화 분당 요청 수 |
| `RATE_LIMIT_IMAGE_PER_MINUTE` | `300` | `/images/` 분당 요청 수 |
| `RATE_LIMIT_DEFAULT_PER_MINUTE` | `120` | 그 외 경로 기본값 |
| `AUDIT_ENABLED` | `true` | 감사 이벤트를 `data/audit/audit.log`에 기록 (`app.audit.*`) |
| `AUDIT_MAX_FILE_SIZE` | `10MB` | 롤링 크기 기준 — Logback `AUDIT_FILE` appender의 롤오버 기준이기도 함 |
| `AUDIT_MAX_HISTORY_DAYS` | `7` | 압축된 감사 파일 보관 일수 |
| `AUDIT_TOTAL_SIZE_CAP` | `100MB` | `data/audit/` 전체 크기 상한 |

> `app.image-description.mode`·`app.image-description.min-image-bytes`는 바인딩만 남아 있고 **읽는 코드가 없습니다** — strip/describe 판단은 업로드 시 "이미지 설명 추가" 체크박스와 `LazyVisionService`의 질의 시점 캐시로 옮겨갔습니다. 그래서 환경변수도 일부러 만들지 않았습니다.

### RAG 튜닝

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|-----------|------|
| `CHUNK_SIZE` | `1500` | 300 ~ 2000 | 문서 청크 크기 (문자 수) |
| `CHUNK_OVERLAP` | `0` | 0 ~ CHUNK_SIZE × 0.25 | 청크 경계 문맥 보완용 중복 문자 수. 기본값 `0` — 섹션 인식 분할이 이미 소제목·부모 헤딩 컨텍스트를 청크에 붙여 주고, `0`이면 문서 내보내기(아래 참고)의 재조립 결과가 원본과 정확히 일치함 |
| `MIN_CHUNK_SIZE` | `500` | 50 ~ CHUNK_SIZE × 0.25 | 너무 작은 청크를 인접 청크와 병합할 최소 길이 기준 (`CHUNK_SPLIT_GRANULAR=true`면 아예 무시됨) |
| `CHUNK_SPLIT_GRANULAR` | `false` | true/false | 청크 분할 전략. `false`=크기 기준 병합(짧은 챕터를 묶어 `CHUNK_SIZE`를 채움). `true`=**소제목마다 분할**, `MIN_CHUNK_SIZE` 무시 — 단 "제목+2문장 이내" 도입부 챕터만 아래 하위 챕터와 통합. 표·코드 블록은 경계를 `CHUNK_SIZE`의 ±50%까지 옮겨 통째로 유지하고(기본 경로는 `CHUNK_OVERLAP`만큼만 옮길 수 있는데 그 기본값이 0), PPTX/PDF는 슬라이드를 넘는 병합을 하지 않음(1슬라이드=1청크 — 슬라이드 내부 섹션은 합치므로 제목만의 청크는 생기지 않음). 핫 수정 가능하지만 **이미 인덱싱된 문서는 재인덱싱해야 전환** — 켠 뒤 문서 하나에 ↺를 눌러 두 전략을 나란히 비교할 수 있음. [OPERATOR_MANUAL.md §6.10](documents/OPERATOR_MANUAL.md#610-청크-분할-전략-크기-기준-병합--소제목-최대-분할) 참고 |
| `SEARCH_TOP_K` | `8` | 2 ~ 15 | 벡터 검색 반환 문서 수 |
| `SEARCH_SIMILARITY_THRESHOLD` | `0.0` | 0.0 ~ 0.75 | 청크 유지 최소 코사인 유사도 (`0.0`=전체 수용) |
| `SEARCH_MULTIQUERY_ENABLED` | `true` | true/false | 검색 전 질의 다중 확장 여부 |
| `SEARCH_MULTIQUERY_MIN_LENGTH` | `15` | 0 ~ 20 | 이 길이 미만 질의는 확장 생략 (`0`=항상 확장). 확장이 실행될 때도 원본 질의 검색이 그 뒤로 대기하지 않고 병렬 실행됨 |
| `SEARCH_HYBRID_ENABLED` | `true` | true/false | RRF에 BM25(FTS5) 키워드 축 추가 (§10.7.2 — FTS 인덱스는 이 플래그와 무관하게 인덱싱 시점에 채워지므로 재인덱싱 불필요) |
| `SEARCH_RETRY_ESCALATE` | `true` | true/false | 재시도마다 후보 풀 확대 — `×(retryCount+1)`, 상한 `×3` |
| `SEARCH_RERANK_ENABLED` | `false` | true/false | RRF 후 LLM 리랭킹 단계 (턴당 LLM 1콜 추가) |
| `SEARCH_CANDIDATE_MULTIPLIER` | `3` | 2 ~ 5 | 리랭킹 후보 풀 크기 — `topK × N` |
| `SEARCH_TAG_CANDIDATE_MULTIPLIER` | `2` | 1 ~ 5 | 태그 선택 시 후보 풀 확대 — `candidateK = max(candidateK, topK × N)` |
| `SEARCH_RRF_KEYWORD_WEIGHT` | `1.0` | 0.5 ~ 3.0 | 가중 RRF(Phase 7-A) — BM25 키워드 축 가중치. 벡터 축(MultiQuery 1~3개)은 항상 `1/축개수`로 그룹 정규화되므로 `1.0`이 정규화된 벡터 그룹과 동일 비중. `SEARCH_HYBRID_ENABLED=false`면 무영향 |
| `SEARCH_RRF_K` | `60` | 20 ~ 100 | 가중 RRF(Phase 7-A) — RRF 순위융합 상수 k(원논문 기본값 60) |
| `SEARCH_CURATED_QA_ENABLED` | `true` | true/false | §10.10 — 좋아요 기반 큐레이션 Q&A(예약 version `"curated"` 네임스페이스에 임베딩)를 RRF 융합에 포함할지 여부. `false`면 해당 검색 자체를 생략 |
| `SEARCH_CURATED_QA_WEIGHT` | `1.2` | 0.5 ~ 5.0 | §10.10 — **좋아요 승격** 큐레이션 축 가중치, 키워드축과 동일하게 그룹 정규화 없이 그대로 적용(벡터축은 항상 `1/축개수`) — `1.0`보다 높아 검증된 답변이 우선 노출되되 순위를 독식하지는 않음 |
| `SEARCH_SUBMISSION_WEIGHT` | `1.5` | 0.5 ~ 5.0 | **지식 제안**(승인된 사용자 제출) 축 가중치. 두 출처는 벡터 네임스페이스와 검색을 공유하되 `MetaKey.CURATED_ORIGIN` 으로 **서로 다른 RRF 축**으로 갈라져, 사람이 직접 쓰고 관리자가 검토한 항목을 좋아요보다 높게(또는 낮게) 따로 조절할 수 있음 |
| `SEARCH_QUERY_EMBED_CACHE_ENABLED` | `true` | true/false | 쿼리 임베딩 캐시(Phase 7-A) — 정규화된 질의 → 벡터를 Caffeine 인메모리 캐시에 저장해 반복·유사 질문의 임베딩 왕복을 생략. 캐시 히트 시 `embed:<model>` usage도 기록 안 됨 |
| `SEARCH_QUERY_EMBED_CACHE_MAX_SIZE` | `500` | 100 ~ 5000 | 쿼리 임베딩 캐시 최대 엔트리 수 |
| `SEARCH_QUERY_EMBED_CACHE_TTL_SECONDS` | `600` | 60 ~ 3600 | 쿼리 임베딩 캐시 TTL(초, write 기준 만료) |
| `MAX_RETRY_COUNT` | `2` | 0 ~ 4 | 증거 부족 시 재검색 최대 횟수 |

### 대화 메모리 / 요약 캐시

대화 이력 주입 길이는 `LLM_MAX_TOKENS × 0.75`(최소 1,000자)로 자동 계산됩니다. 원문 그대로 보내는 폴백 경로와 아래 요약 캐시 경로 모두 이 예산을 그대로 지키므로, 두 경로 사이를 오가도 LLM에 전달되는 컨텍스트 양은 항상 동일하게 유지됩니다.

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `MEMORY_FETCH_LIMIT_TURNS` | `50` | 폴백 경로에서 문자 예산 적용 전 조회할 최근 turn 상한 |
| `SUMMARY_MAX_CACHED_THREADS` | `3` | 요약 캐시(LRU)가 동시에 유지하는 최대 thread 수 |
| `SUMMARY_MAX_SUMMARY_CHARS` | `2000` | 생성된 요약 문자열의 상한 (초과 시 잘림) |
| `SUMMARY_RECENT_RAW_TURNS` | `2` | 요약 뒤에 원문 그대로 덧붙일 최근 turn 수 (이 turn들도 예산 안에서 최신 우선으로 채워짐) |
| `SUMMARY_PRECOMPUTE_TTL_SECONDS` | `15` | 동일 thread에 대한 중복 요약 사전계산(precompute) 억제 창(초) |

> 형식별 분할 전략 상세 → [USER_MANUAL.md §4.1](documents/USER_MANUAL.md#41-형식별-청크-분할-전략)

로컬 LLM (LM Studio, Ollama 등) 사용 시 — `.env`만 설정하면 됩니다:
```env
LOCAL_LLM_URL=http://localhost:1234/v1
# LOCAL_LLM_KEY는 로컬 엔드포인트에선 선택 (비우면 no-key 치환)
LOCAL_LLM_KEY=
LOCAL_LLM_MODEL=google/gemma-4-e4b
EMBED_MODEL=text-embedding-nomic-embed-text-v1.5
```

## 구성

```
rag_java/
├── pom.xml                            # Spring Boot 3.5.15 + Spring AI 1.1.8
├── Dockerfile / docker-compose.yml
├── .env.example
├── scripts/
│   ├── install-hooks.sh               # 클론 후 1회 실행으로 git 훅 활성화
│   └── hooks/
│       └── pre-commit                 # .env 우발 커밋 방지
└── src/main/
    ├── java/com/example/ragagent/
    │   ├── agent/
    │   │   ├── AgentState.java        # 불변 record — 노드 간 파이프라인 상태
    │   │   └── AgentGraph.java        # 그래프 실행 엔진 (switch expression)
    │   ├── config/
    │   │   ├── AppProperties.java     # @ConfigurationProperties (LlmConfig 포함)
    │   │   └── WebConfig.java         # ChatClient 빈 + CORS + i18n (CookieLocaleResolver)
    │   ├── audit/
    │   │   └── AuditLogger.java                # 감사 이벤트 → Logback AUDIT_FILE appender
    │   ├── context/
    │   │   ├── ThreadContext.java              # 요청별 record (threadId, userId, locale)
    │   │   └── ThreadContextResolver.java      # HandlerMethodArgumentResolver
    │   ├── controller/
    │   │   ├── ChatController.java             # REST POST /api/v1/chat; HTMX /ui/chat, /ui/chat/stream, 스레드 관리
    │   │   ├── DocumentController.java         # REST /api/v1/documents, /api/v1/images; 비동기 업로드 (202+taskId)
    │   │   ├── OperationsController.java       # REST GET /api/v1/health, /api/v1/llm/usage; HTMX 스레드 목록 + LLM 카드
    │   │   ├── AdminController.java            # /admin, /admin/chunks; 문서 재인덱스; 큐레이션 Q&A + 청크 추가 제안 검토
    │   │   ├── CuratedSubmissionController.java # /curated/submissions — 청크 추가 게시판(등록·철회·미확인 배지)
    │   │   ├── SettingsController.java         # /settings 조회 + /admin/settings/update|reset
    │   │   ├── AuthController.java             # /login, /signup, /setup 페이지 컨트롤러; 회원가입 후 자동 로그인
    │   │   ├── GlobalExceptionHandler.java     # RFC 9457 ProblemDetail; 400/413 처리
    │   │   └── GlobalModelAdvice.java          # @ControllerAdvice; authEnabled 모델 속성 전체 뷰 주입
    │   ├── exception/                          # 도메인 예외 클래스
    │   ├── ingestion/
    │   │   ├── DocumentIndexer.java            # 핵심 인덱싱 로직; 3단계 동기화; DocRegistry SQLite
    │   │   ├── DocRegistry.java                # doc_registry SQLite 테이블 관리
    │   │   ├── VectorStoreFacade.java          # VectorStoreProvider 위임 (백엔드 불가지론)
    │   │   └── VectorStoreProvider.java        # chroma | sqlite-vec (app.vectorstore.type)
    │   ├── ratelimit/
    │   │   └── RateLimitFilter.java            # Bucket4j + Caffeine 유저별 토큰버킷; 429 + RAG-RATE-001
    │   ├── llm/
    │   │   ├── LlmRouter.java             # 멀티 프로바이더 라우팅: TaskType × RoutingMode; executeGated()/acquirePermit() — 채팅/질의 경로 프로바이더별 동시성 게이트 + 429 백프레셔
    │   │   ├── ConcurrencyLimitingChatModel.java  # ChatModel 데코레이터 — executeGated()를 우회하는 프레임워크 내부 호출자(MultiQueryExpander)에 동시성 게이트 적용
    │   │   ├── RoutingMode.java           # COST_FIRST|QUALITY_FIRST|PROGRESSIVE|LOCAL_ONLY
    │   │   ├── CircuitBreaker.java        # LLM 프로바이더 인메모리 차단 관리 (Retry-After 지원)
    │   │   ├── TrackingEmbeddingModel.java  # EmbeddingModel 데코레이터 — 임베딩 토큰 사용량을 채팅과 분리 기록 (embed:<model>)
    │   │   ├── CachingEmbeddingModel.java   # EmbeddingModel 데코레이터 — Caffeine 쿼리 임베딩 캐시(Phase 7-A) + 인플라이트 single-flight 중복 제거(ConcurrentHashMap<key,CompletableFuture>), tracking 바깥쪽에 합성
    │   │   └── LoadBalancingEmbeddingModel.java  # EmbeddingModel 데코레이터 — 다중 임베딩 엔드포인트 least-in-flight 분산 (§6.21 E1)
    │   ├── model/                         # Java 21 record
    │   │   ├── MetaKey.java               # 벡터 스토어 메타데이터 키 상수
    │   │   └── ChatRequest/Response/SourceRef/DocumentInfo/SyncResult/ThreadMeta/ChatForm/LlmProviderReport/IndexingProgressEvent.java
    │   ├── security/
    │   │   ├── FileTypeDetector.java      # 매직바이트 검증 (PDF, DOCX/PPTX, TXT/MD)
    │   │   └── PromptInjectionGuard.java  # 입력 검증 + API 키 마스킹
    │   ├── repository/
    │   │   ├── MemoryRepository.java              # 대화 메모리 추상 인터페이스 (getTurns 포함)
    │   │   ├── SqliteMemoryRepository.java        # SQLite WAL 기반 구현
    │   │   ├── LlmUsageRepository.java            # LLM 토큰 사용량 SQLite 저장소
    │   │   ├── CuratedQaRepository.java           # curated_qa — 좋아요 승격 + 승인된 사용자 제안(origin=like|manual)
    │   │   ├── CuratedSubmissionRepository.java   # curated_submission — 청크 추가 게시판(검토 대기/등록/반려)
    │   │   └── ImageDescriptionRepository.java    # image_descriptions 테이블 CRUD (Vision 캐시)
    │   └── service/
    │       ├── AgentService.java              # 에이전트 파이프라인 진입점
    │       ├── StreamingAgentService.java     # SSE 스트리밍 파이프라인 오케스트레이터
    │       ├── GraphListener.java             # 노드/토큰/출처 이벤트 hook 인터페이스
    │       ├── ClassifierService.java         # 질문 유형 분류 노드
    │       ├── DirectAnswerService.java       # meta 질문 직접 응답 노드
    │       ├── RetrievalService.java          # 벡터 검색 노드 + LazyVision 보강
    │       ├── AnswerService.java             # 답변 생성 + 스트리밍 + 증거 충분성 검증
    │       ├── CriticService.java             # 근거 검증 노드
    │       ├── FinalizeService.java           # 대화 메모리 저장 노드
    │       ├── MemoryService.java             # 멀티턴 메모리 — SQLite 영속
    │       ├── RagService.java                # 문서 인덱싱 + 동기화 + 이미지 정리
    │       ├── AdminService.java              # Admin UI 데이터 (청크 조회/편집 + 벡터 스토어 상태) — chroma·sqlite-vec
    │       ├── CuratedQaService.java          # 큐레이션 Q&A 축: 좋아요 승격 + 관리자 승인 제안, 임베딩/de-index
    │       ├── CuratedSubmissionService.java  # 청크 추가 게시판: 입력 검증+태그, 승인 시 분할(1:N), 거부, 알림 카운트
    │       ├── SettingsService.java           # 런타임 설정 오버라이드 레이어(AppProperties.OverrideSource) + /settings 조회/검증/감사
    │       ├── IndexingProgressService.java   # 비동기 업로드/동기화 SSE 진행 이벤트 관리
    │       ├── MarkdownCorrectionService.java # LLM 마크다운 출력 후처리
    │       ├── DocumentLoaderService.java     # PDF/DOCX/TXT/MD 로더 + 마크다운 섹션 파서; 스캔 PDF OCR
    │       ├── DocxToMarkdownConverter.java   # DOCX → Markdown + 인라인 이미지 추출
    │       ├── PptxToMarkdownConverter.java   # PPTX → Markdown (슬라이드별 [페이지: N] 마커=섹션 경계; 제목 있으면 ## 헤딩; SmartArt/차트/하이퍼링크 텍스트; 중복/목차/구분 슬라이드 제거)
    │       ├── PdfToMarkdownConverter.java    # 비스캔 PDF → Markdown (페이지별 [페이지: N] 마커=섹션 경계; 합성 헤딩 없음)
    │       ├── ImageExtractorService.java     # 스캔 PDF 전용 이미지 추출 오케스트레이터(다른 포맷은 각자 변환기에서 인라인 처리)
    │       ├── PdfImageExtractor.java         # PDFBox PDImageXObject 기반 PDF 이미지 추출
    │       ├── PptxImageExtractor.java        # POI XSLFPictureShape 기반 PPTX 이미지 추출 + 그리기 도구 래스터라이즈 + SmartArt/차트/OLE 그래픽 프레임
    │       ├── VisionDescriptionService.java  # 이미지 → 한국어 설명 (Vision LLM)
    │       ├── LazyVisionService.java         # 검색 시점 Vision 설명 생성 + SQLite 캐시
    │       ├── ImageTypeClassifier.java       # 이미지 유형 분류 → 전용 프롬프트 선택
    │       ├── OcrService.java                # Tesseract OCR — 스캔 PDF (kor+eng)
    │       ├── EmfToPngConverter.java         # Batik WMFTranscoder→SVG→PNGTranscoder 파이프라인
    │       ├── LibreOfficeConverter.java      # LibreOffice headless WMF→PNG (20s 타임아웃)
    │       ├── ThreadMetaService.java         # 대화 스레드 메타 관리
    │       └── VectorStoreRegistry.java       # 버전별 ChromaVectorStore 관리 (chroma 백엔드)
    └── resources/
        ├── application.properties
        ├── messages.properties            # UI 문자열 — English (기본)
        ├── messages_ko.properties         # UI 문자열 — 한국어
        ├── static/
        │   ├── css/
        │   │   ├── app.css                # 커스텀 스타일 (버블·애니메이션·반응형 오프캔버스/dvh/16px/44px)
        │   │   └── theme.css              # 라이트/다크 CSS 변수 + Bootstrap 다크 모드 오버라이드
        │   ├── manifest.webmanifest       # PWA 매니페스트 (이름·아이콘·standalone)
        │   ├── sw.js                      # 서비스 워커 (NETWORK-FIRST, 오프라인 fallback 전용)
        │   ├── offline.html               # 오프라인 fallback 페이지 (자체 완결 정적 HTML)
        │   ├── icons/icon.svg             # 앱 아이콘 (SVG, any maskable)
        │   └── js/
        │       └── chat-stream.js         # SSE 스트리밍 클라이언트 (fetch + ReadableStream)
        └── templates/
            ├── layout/base.html           # 공통 레이아웃 (Thymeleaf Layout Dialect; PWA meta + SW 등록)
            ├── chat.html                  # 채팅 페이지 (이전 turn 서버 렌더 포함)
            ├── documents.html             # 문서 관리 페이지
            ├── curated-submissions.html   # 지식 제안 게시판 (등록 폼 + "내 제안" 상태 목록)
            ├── llm-usage.html             # LLM 사용량 통계 페이지
            └── fragments/
                ├── admin-curated.html     # 관리자 큐레이션 Q&A 패널 (펼칠 때 지연 로딩)
                ├── admin-submissions.html # 관리자 청크 추가 제안 검토 패널 (지연 로딩, 상태 필터)
                ├── llm-usage-cards.html   # 프로바이더 카드 (HTMX 30초 자동 갱신)
                ├── thread-list.html       # HTMX 스레드 목록 fragment
                ├── thread-item.html       # HTMX 스레드 아이템 fragment
                ├── doc-row.html           # HTMX 문서 테이블 행 fragment
                ├── doc-table-body.html    # HTMX 문서 테이블 tbody fragment
                ├── message-user.html      # 사용자 메시지 버블 fragment
                ├── message-assistant.html # HTMX 답변 버블 (출처 hover preview 포함)
                └── message-error.html     # HTMX 에러 버블 fragment
```

## 에이전트 파이프라인

```
질문 입력
  └─▶ [Classifier]  → 질문 유형 분류 (concept / usage / error / version / meta)
        ├─ meta  ──▶ [DirectAnswer] → [Finalize] → 응답
        └─ other ──▶ [Retrieval]   (LLM 최적 쿼리 생성 → 벡터 검색)
                       └─▶ [Answer]   (구조화 답변 + sufficient 자기평가)
                              ├─ 증거 부족 ──▶ [Retrieval] (최대 2회)
                              └─ 충분    ──▶ [Critic]   (근거 검증)
                                              ├─ 미근거  ──▶ [Retrieval]
                                              └─ 근거 OK ──▶ [Finalize] → 응답
```

## 주요 기능

- **인증** — Spring Security 폼 로그인, BCrypt(12) 비밀번호 해싱, 5회 실패 시 15분 계정 잠금, `/login`·`/signup`·`/setup`. `app.auth.enabled=false`로 로컬 no-login 배포 가능; `app.auth.management-only=true`는 채팅·조회는 게스트에 열어두고 문서 관리·`/admin`만 로그인 요구 — [OPERATOR_MANUAL.md §9.4.2](documents/OPERATOR_MANUAL.md#942-관리-전용-인증-management-only) 참고
- **Web UI** — Thymeleaf + HTMX 기반 채팅·문서 관리·LLM 사용량 화면, KO/EN 언어 전환
- **SSE 실시간 스트리밍** — 노드별 단계 배지(classifier→retrieval→answer→critic) + 토큰 실시간 표시 (`chat-stream.js`, fetch + ReadableStream)
- **다크 모드** — CSS 변수 기반 라이트/다크 전환, `prefers-color-scheme` 자동 감지 + `localStorage` 사용자 override
- **모바일 & PWA** — 반응형 오프캔버스 대화 드로어, `100dvh` 하단 고정 입력창, `table-responsive` 가로 넘침 처리, iOS 16px 자동 확대 방지; 설치형 PWA(`manifest.webmanifest`, 인증/RAG/SSE 응답을 캐시하지 않는 오프라인 fallback 서비스 워커, iOS "홈 화면에 추가" 힌트); 아이콘 버튼 i18n `aria-label`·44px 터치 영역·`:focus-visible` 표시
- **질문 분류 + 라우팅** — meta(인사·잡담)는 RAG 없이 직접 응답, 나머지는 풀 파이프라인
- **멀티 LLM 라우팅** — `LlmRouter`가 `TaskType × RoutingMode` 기준으로 프로바이더 선택: COST_FIRST / QUALITY_FIRST / PROGRESSIVE / LOCAL_ONLY
- **Circuit Breaker** — HTTP 429/오류 시 프로바이더 자동 차단 (Retry-After 지원), 우선순위 기반 failover; LLM 사용량 대시보드에서 차단 상태 확인
- **프로바이더별 동시성 게이트 + 백프레셔** — 채팅/질의 경로가 한 프로바이더에 보내는 동시 요청은 그 서버가 처리 가능한 만큼(`--parallel`)을 절대 넘지 않음; `LLM_PERMIT_WAIT_TIMEOUT_SECONDS`(기본 60초)를 넘겨 대기하면 600초 read timeout까지 기다리지 않고 즉시 HTTP 429 + `Retry-After` 응답. 인덱싱/백그라운드 LLM 호출은 영향받지 않음(자체 세마포어 유지)
- **인플라이트 single-flight (임베딩)** — 완전히 동일한(정규화 후) 텍스트를 동시에 요청하면(예: 여러 사용자가 거의 동시에 같은 질문) 첫 호출만 실제로 계산하고 나머지는 그 결과를 공유(`CachingEmbeddingModel`) — 각자 다시 계산하지 않음
- **과부하 인지 서킷브레이커** — 폴백 프로바이더가 없는 상태에서(예: 단일 LOCAL 배포) 429/402/503을 받으면 기본 다중 분 단위 차단 대신 30초로 짧게 차단해 일시적 용량 초과가 채팅 전체를 다운시키지 않음 — 다른 프로바이더로 넘길 수 있는 상황이면 기존처럼 정상 차단 후 자동 폴백
- **동일 우선순위 로드밸런싱** — 같은 role·priority로 프로바이더를 여러 대 등록하면(예: 로컬 서버 2대) 동시성 게이트 여유가 더 많은 쪽으로 요청이 자동 분산(least-in-flight) — 코드 변경 없이 배포 설정만으로 처리량 수평 확장
- **태스크별 모델 라우팅 (소형 LLM 오프로딩)** — 추론이 필요 없는 잡무(키워드+맥락 추출·대화 요약·제목 생성·MultiQuery 쿼리 확장)는 `TaskType.MICRO_TEXT`로 라우팅됨. `type=MICRO_TEXT` 소형(~500MB) 로컬 모델을 등록하면 이 잡무만 소형으로 내려가고, 답변·품질 민감한 분류/meta 직답은 큰 모델이 전담. 소형 미등록 시 큰 모델이 흡수(회귀 0) — [LLM_ROUTING.md §9](documents/LLM_ROUTING.md) 참고
- **임베딩 로드밸런싱 + 병렬 서브배치 임베딩** — 다중 임베딩 엔드포인트(`EMBED_ADDITIONAL_BASE_URLS`, 동일 모델·차원)를 least-in-flight로 분산; 인덱싱 시 한 문서의 서브배치를 병렬 임베딩(`EMBED_MAX_CONCURRENT_BATCHES`)해 엔드포인트를 채움. 둘 다 opt-in(기본 단일 엔드포인트·직렬) — [OPERATOR_MANUAL §3.2](documents/OPERATOR_MANUAL.md) 참고
- **설정 페이지(`/settings`)** — 유효 LLM/RAG 설정(프로바이더·라우팅·임베딩·검색 튜닝)을 한 화면에서 조회. 세 그룹의 값이 **재기동 없이 핫 수정** 가능(`settings_override` 테이블에 영속, 삭제 시 프로퍼티 기본값 복귀): 검색 튜닝(유사도 임계값·RRF 가중치/k·후보 배수·멀티쿼리 최소 길이/활성화·재시도 확대·topK·하이브리드 검색 — 다음 검색부터 적용), 인덱싱/청킹(청크 크기/오버랩/최소 크기·**청크 분할 전략**·동시 파일/LLM 호출 수 제한 — 다음 인덱싱/↺ 재인덱싱부터 적용), Direct 답변 temperature(다음 Direct 호출부터 적용). 수정은 관리자 전용이며 감사 로그에 기록되고, 재기동 필요 값(rerank-enabled·일반 temperature/max-tokens·임베딩 설정 등)은 조회 전용으로 표시
- **벡터 검색** — `MultiQueryExpander`(3쿼리 병렬, 짧은 키워드형 질문은 확장 생략)로 최적 검색 후 선택된 백엔드(ChromaDB 또는 sqlite-vec)로 유사도 검색. 원본 질문 검색은 쿼리 확장과 병렬로 실행되어 확장 대기 뒤로 밀리지 않음. Chroma 배치 검색은 실제로 읽는 메타데이터/문서/거리 필드만 요청하고 쓰지 않는 임베딩 벡터는 요청하지 않아, 후보 풀이 큰 경우에도 응답이 가볍게 유지됨
- **Contextual Retrieval** — 청크 임베딩과 키워드 검색(`chunk_fts`) 입력 앞에 맥락 헤더(`{파일명} > {섹션 제목}` + 키워드 추출과 같은 호출에서 생성되는 LLM 1~2문장 요약)를 결합해, 표·코드 조각·대명사 위주 텍스트처럼 단독으로는 모호한 청크의 검색 재현율을 높임. 이 헤더는 저장·표시 텍스트, 출처 미리보기, 답변 프롬프트에는 절대 나타나지 않고 임베딩/키워드 검색 입력에만 반영됨
- **임베딩 입력 정규화** — 마크다운 장식(구분선, 볼드/이탤릭/밑줄 마커)을 임베딩·`chunk_fts`·답변 프롬프트 입력에서만 제거(저장·표시 텍스트는 원문 유지)해 검색 인덱스 노이즈와 프롬프트 토큰 사용량을 줄임
- **응답 길이 모드 (S/M/L)** — 메시지별 토글로 답변 분량을 조절. 각 모드의 목표치는 `LLM_MAX_TOKENS` 비율(15%/40%/70%)과 고정 글자수 하한(2,000/5,000/10,000자) 중 큰 값이라, 설정값이 작아도 S와 M이 뚜렷이 구분됨. 이 값은 블로킹 호출의 `maxTokens`로도, 프롬프트의 "약 N자" 스타일 지시문으로도 그대로 재사용됨 — 스트리밍 응답은 설계상 호출당 토큰 상한이 없어 지시문이 유일한 조절 수단. `L`(원문 최대)은 검색 컨텍스트가 있을 때만 의미가 있어 Direct 모드에서는 비활성화됨
- **좋아요 기반 큐레이션 Q&A (§10.10)** — 답변에 좋아요를 누르면 별도로 임베딩되어(예약 벡터스토어 버전 네임스페이스, 문서 재인덱싱에도 보존) 이후 검색에 가중 RRF 축으로 융합됨(`SEARCH_CURATED_QA_ENABLED`/`SEARCH_CURATED_QA_WEIGHT`, `/settings`에서 핫 수정 가능). 정답을 그대로 반환하지 않고 근거로만 주입해 LLM이 현재 문서와 대조함. 좋아요를 누른 본인은 채팅 버블에서 바로 수정(자동 재임베딩) 가능하고, 관리자는 `/admin` 카드에서 전체 사용자의 큐레이션 항목을 편집·강제 삭제(좋아요 여부와 무관)할 수 있음. **L모드** 답변은 좋아요를 눌러도 임베딩되지 않음 — 이미 인덱싱된 원본 문서 내용과 사실상 동일해 재임베딩이 불필요하기 때문(좋아요 자체는 정상 기록됨). 승격된 답변은 **질문 당시의 검색 스코프(태그)를 승계**해(turn 단위로 저장) 태그를 좁힌 검색에서도 살아남고, 스코프를 알 수 없는 항목은 어느 스코프에도 속하지 않는 대신 **모든 스코프에 속한 것으로** 취급된다 — 이 처리가 없던 때는 태그 칩을 하나라도 켜면 큐레이션 항목이 전부 결과에서 사라졌다
- **청크 추가 게시판 (사용자 제안 → 관리자 임베딩)** — 사용자가 `/curated/submissions`에서 제목+본문으로 지식을 등록하면, 관리자가 `/admin` 전용 카드에서 검토해 **임베딩 실행**하거나 **사유를 적어 거부**한다. 승인된 제안은 좋아요 큐레이션 Q&A와 같은 검색 축으로 들어가므로 `SEARCH_CURATED_QA_*` 설정이 그대로 적용되고, 게시판 자체는 별도 테이블이라 `curated_qa.status='active'`가 "지금 검색에 기여 중"이라는 의미를 그대로 유지한다. **관리자 승인이 사용자 작성 텍스트와 답변 프롬프트의 `[검색된 문서]` 블록 사이의 유일한 관문**이라, 검토 화면은 항상 본문 전문을 보여주고 일괄·자동 승인 경로는 의도적으로 만들지 않았다. 알림은 양방향 60초 헤더 배지 폴링 — 관리자에게는 검토 대기 건수(로그인 직후 첫 폴링에 바로 표시), 작성자에게는 처리 결과("내 제안"을 열면 읽음 처리). **본문 길이 제한은 없다**: 승인 시 본문이 `ChunkSplitter`를 그대로 통과해 N개 큐레이션 행으로 등록되므로(문서 인덱싱과 같은 기계라 `CHUNK_SPLIT_GRANULAR`·표/코드 블록 보호까지 적용) "너무 길어 임베딩 불가"라는 실패 모드가 입력을 거부하는 대신 구조적으로 사라진다. 상태는 **전부/전무** — 청크가 하나라도 살아 있으면 등록 완료, 전부 내려가면 회수됨이며 청크 하나를 지우면 제안 전체가 함께 내려간다(반쪽 등록 상태가 생기지 않음). 본문은 마크다운으로 작성·미리보기하고(등록 폼·관리자 검토 화면 모두 DOMPurify 경유), 태그를 달면 그 태그를 고른 검색에만 반영되며 태그가 없으면 모든 스코프에서 검색된다. [OPERATOR_MANUAL.md §6.9](documents/OPERATOR_MANUAL.md#69-청크-추가-게시판-사용자-제안--관리자-임베딩) 참고
- **ReAct 재검색** — 증거 부족 시 최대 2회 자동 재검색
- **Critic 검증** — 생성된 답변이 문서에 근거하는지 LLM이 이중 검증
- **PROGRESSIVE 모드** — COST_FIRST로 시작 → 품질 임계값 미달 시 PREMIUM 프로바이더로 재실행 + 업그레이드 배지 표시
- **no-auth 모드 방문자별 채팅 분리** — `app.auth.guest-identity`(`shared`/`ip`/`cookie`/`hybrid`)로 접속자마다 사이드바 스레드·대화 이력을 분리. 저장 계층 변경 0 — 모든 테이블이 이미 `user_id` 축으로 격리돼 있어 인증 필터가 주입하는 id 한 곳만 방문자별로 바꾸면 됨. `hybrid`(권장)는 장수 `rag_visitor` 쿠키가 있으면 그것을, 없으면 접속 IP에서 유도해 쿠키로 저장 — DHCP 갱신(쿠키가 이김)과 쿠키 삭제(같은 IP면 복구)를 모두 견딤. id는 영속 서버 키로 HMAC한 `guest-<hex>`라 원문 IP가 DB에 남지 않고, 접두사 덕분에 나중에 실계정으로 이관·정리할 대상을 식별할 수 있음. 업로드 문서는 공유 유지. 기본값 `shared`(회귀 0)
- **클라이언트 IP 신뢰 판정 일원화** — `app.trust-forwarded-for`(기본 `false`)가 속도 제한과 방문자 식별 양쪽에서 `X-Forwarded-For` 신뢰 여부를 결정. 끄면 헤더를 위조해도 속도 제한을 리필하거나 다른 방문자 신원을 가로챌 수 없고, 켜면(Caddy 등 프록시 뒤에서는 필수) 모든 방문자가 프록시 IP 하나로 뭉치지 않고 실제 IP로 식별됨
- **속도 제한** — Bucket4j + Caffeine 유저별 토큰버킷; 429 `RAG-RATE-001` + `Retry-After` 헤더; `app.rate-limit.*`로 설정
- **감사 로그** — Logback 롤링 파일에 구조화된 이벤트 기록; `app.audit.*`로 설정
- **이미지 처리 파이프라인** — PDF/PPTX/DOCX 이미지 추출 → `data/images/{imageId}/` 저장(문서 SHA-256 기반 해시 키 — 문서 자체의 `docId`와는 별개이며, 긴 파일명이 이미지마다 반복 저장되는 것을 방지); PPTX에서 사진 위에 강조 원·화살표 같은 주석 도형이 겹쳐 있으면 하나의 합성 이미지로 병합(`app.pptx-image.merge-annotated-pictures`), 표 위에 겹친 주석 도형도 표+도형 합성(표는 MD 표로도 유지)하며 실제 Ctrl+G 그룹·SmartArt는 각 한 장으로 유지; 앵커에 안 겹친 느슨한 도형끼리의 병합은 `app.pptx-image.rasterize-shapes=true`일 때만(기본 off). DOCX도 사진과 같은 문단의 레거시 VML 주석 도형(사각형/원/선)을 하나로 병합(`app.docx-image.merge-annotated-shapes` — POI가 DOCX 도형 좌표를 노출하지 않아 같은 문단 근사 방식); 검색 시점 Lazy Vision 설명 생성 (SQLite 캐시); 답변 버블에 이미지 썸네일 표시
- **채팅 중 이미지 분석 진행 표시 + 건너뛰기** — 검색 결과에 아직 청크 텍스트에 설명이 임베딩되지 않은 이미지가 포함돼 있으면 답변 생성 전에 Lazy Vision이 해당 이미지를 분석하며, 헤더 배지에 "이미지 분석 중 (2/5)"처럼 분석 완료 개수가 실시간으로 표시됨. 옆의 **건너뛰기** 링크는 *대기*만 중단할 뿐 — 분석 자체는 백그라운드에서 계속 진행되어 SQLite 캐시에 저장되므로 다음에 같은 이미지가 다시 검색되면 즉시 재사용됨. 업로드 시 "이미지 설명 추가"로 이미 `[이미지 설명: ...]`이 청크에 임베딩된 이미지는 쿼리 시점에 재분석하지 않음
- **이미지 유형 분류** — diagram / screenshot / chart / photo / other 분류 후 유형별 전용 Vision 프롬프트 적용
- **스캔 PDF OCR** — Tesseract OCR (kor+eng)로 텍스트 없는 페이지 처리 (`app.image-description.ocr-enabled=true`)
- **EMF/WMF 변환** — DOCX Windows Metafile 이미지를 Batik(EMF) 또는 LibreOffice headless(WMF)로 PNG 변환
- **멀티턴 대화** — `thread_id` 기반 대화 이력 유지 (SQLite WAL, 재시작 후에도 영속)
- **메시지 버블 복원** — `/chat/{threadId}` 재진입 시 이전 turn 메시지 버블 서버 렌더링
- **출처 hover 미리보기** — `SourceRef` 구조체 기반 Bootstrap Popover, 출처 hover 시 청크 텍스트 200자 미리보기; 모바일이 아닌 화면에서는 팝오버 폭을 약 2배로 넓히고 글자 크기를 살짝 줄여 줄바꿈을 줄임
- **청크 편집기 실시간 미리보기** — 넓은 PC 화면에서는 `/admin` 청크 편집 오프캔버스가 마크다운(이미지·표 포함) 실시간 미리보기와 텍스트 편집창으로 나뉘어 표시되며, 입력하는 대로 미리보기가 갱신됨. 좁은 화면은 기존처럼 편집창만 표시
- **소제목 번호 생성 기본값 자동 조정** — 업로드 시 "소제목 숫자 생성" 체크박스가 PPTX를 선택하면 자동으로 해제됨(PPTX에는 서버에서 애초에 적용되지 않음; PDF는 영향 없이 체크 유지), PPTX와 다른 형식을 함께 선택하면 옵션이 파일별이 아니라 배치 전체에 하나만 적용되는 구조상 나눠서 업로드하라는 경고가 표시됨
- **문서 내보내기 (MD/TXT/DOCX)** — 문서 목록 각 행의 **내보내기** 버튼(관리자 전용)이 저장된 변환 MD가 아니라 현재 색인된 청크를 기준으로 문서를 재구성함 — `/admin` 청크 편집이 그대로 반영됨. `ChunkReassembler`가 `ChunkSplitter`가 검색을 위해 일부러 벌여 놓은 중복(재주입된 소제목, 부모 챕터 breadcrumb, 잘린 코드펜스 마커, 반복된 표 헤더, 슬라이딩 윈도우 overlap)을 렌더링 전에 걷어내 원문에 가까운 결과를 만듦 — 실제 335청크 문서로 검증한 결과 원본 대비 글자 수 오차 0.001%. MD는 이미지가 있으면 ZIP으로 함께 받고(원본 파일이 사라진 이미지는 깨진 링크 대신 `(이미지 없음: …)` 안내), DOCX는 POI로 이미지를 위치에 맞게 임베드함 — 글머리표 뒤·문장 중간 마커는 가운데 정렬된 그림 문단으로 내려가고, 표 셀 안 마커는 그 칸 안에 칸 너비로 삽입됨. 코드 블록은 테두리가 있는 1×1 표 안에 좌측 정렬·고정폭으로 렌더링되며 `//`·`#`·`/* … */` 주석만 초록색으로 표시(문자열 리터럴을 추적하므로 `"http://…"`는 칠하지 않음). 문서별 실제 인덱싱 당시 `CHUNK_OVERLAP` 값이 `doc_registry`에 기록되어(기존 문서는 기동 시 자동 백필) 이후 설정을 바꿔도 예전 문서의 내보내기 결과가 틀어지지 않음. PPTX 내보내기는 아직 미지원
- **코드 syntax highlight** — DOMPurify sanitize 후 highlight.js 적용, 다크 모드 연동
- **LLM 사용량 대시보드** — 프로바이더별 일간·주간·월간 토큰 사용량, Chart.js 일별 히스토리 차트, Circuit Breaker 카운트다운; 임베딩 사용량은 채팅과 분리 집계(`embed:<model>`, usage 미반환 서버는 근사치 폴백); 사용 이력 없는 비활성 프로바이더는 자동 숨김, 설정에서 제거된 orphan 기록은 관리자가 카드에서 삭제 가능
- **문서 버전 관리** — 버전별 격리 (chroma: 컬렉션 분리 / sqlite-vec: `version` partition key)
- **증분 인덱싱** — SHA-256 기반 변경 감지, `doc_registry` SQLite 테이블 영속 (유저별). sqlite-vec에서는 토큰 서브배치가 임베딩되는 즉시 그 서브배치만 삽입하며 문서 전체 분량을 버퍼링하지 않아, 대용량 문서 인덱싱 시 피크 메모리가 문서 크기가 아니라 서브배치 크기에 비례
- **키워드 추출 배치화** — 인덱싱 시 청크 N개(기본 2, `INDEXING_KEYWORD_BATCH_SIZE`)를 하나의 LLM 호출로 묶어 처리, 청크당 1콜이던 왕복 횟수를 대략 1/N로 절감. 배치 호출/파싱 실패 시 해당 청크는 개별 TF 키워드 추출로 폴백
- **다양한 문서 형식** — PDF, PPTX, DOCX, TXT, MD
- **PPTX/PDF → Markdown 변환 정리** — 비스캔 PDF·PPTX는 Markdown으로 변환되며 `[페이지: N]` 마커(합성 헤딩이 아님)가 페이지/슬라이드 단위 섹션 경계 역할을 함. PPTX는 추가로 이미지 없는 중복 슬라이드, 목차/agenda 슬라이드(불릿이 다른 슬라이드 제목들과 대부분 일치), 제목만 있는 섹션 구분 슬라이드("PART 2"·"목차"·"결제 시스템" 같은 번호/키워드/짧은 명사구 제목)를 제거해 내용 없는 슬라이드가 검색 인덱스에 남지 않게 함(문장형 키 메시지 제목은 유지 — `app.pptx-remove-duplicate-slides`·`app.pptx-drop-divider-slides`, 둘 다 기본 on)
- **Java 21 Virtual Threads** — LLM I/O 및 병렬 인덱싱 전체에 경량 스레드 적용

## 엔드포인트

### Web UI

| Method | Path | 설명 |
|--------|------|------|
| `GET` | `/` | 채팅 홈 (새 스레드 생성) |
| `GET` | `/chat/{threadId}` | 기존 스레드 채팅 화면 (이전 메시지 버블 복원) |
| `GET` | `/documents` | 문서 관리 화면 |
| `GET/POST` | `/curated/submissions` | 지식 제안 게시판 — 청크 직접 등록 + 처리 결과 확인 |
| `GET` | `/llm-usage` | LLM 사용량 통계 페이지 |

### REST API

| Method | Path | 설명 |
|--------|------|------|
| `GET` | `/api/v1/health` | 헬스 체크 |
| `POST` | `/api/v1/chat` | 질문 → 답변 |
| `POST` | `/api/v1/documents` | 문서 업로드 + 인덱싱 |
| `POST` | `/api/v1/documents/sync` | 증분 동기화 |
| `GET` | `/api/v1/documents` | 인덱싱된 문서 목록 |
| `DELETE` | `/api/v1/documents/{docId}` | 문서 삭제 |
| `GET` | `/api/v1/images/{docId}/{filename}` | 추출된 이미지 파일 서빙 |
| `GET` | `/api/v1/llm/usage` | 프로바이더별 토큰 사용량 + Circuit Breaker 상태 |
| `GET` | `/api/v1/llm/usage/history` | 일별 토큰 히스토리 (`?days=7\|30\|90`) |
