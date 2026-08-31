# RAG Agent — 에러 코드 표

모든 도메인 예외는 `RagException` 계층에서 발생하며 `GlobalExceptionHandler`가 RFC 9457 ProblemDetail로 변환합니다.
응답 본문에는 `errorCode`와 `traceId` 프로퍼티가 포함됩니다. `X-Trace-Id` 응답 헤더로도 확인 가능합니다.

| 에러 코드 | HTTP | 예외 클래스 | 설명 |
|---|---|---|---|
| RAG-VAL-001 | 400 | `InvalidQuestionException` | 잘못된 질문 (빈 값, 길이 초과 등) |
| RAG-UP-001  | 422 | `UnsupportedFileTypeException` | 미지원 파일 타입 또는 매직바이트 불일치 |
| RAG-UP-003  | 413 | (MaxUploadSizeExceededException) | 파일 크기 초과 |
| RAG-INDEX-001 | 500 | `DocumentIndexingException` | 인덱싱 실패 (SHA-256 연산, 청크 저장 등) |
| RAG-VEC-001 | 503 | `VectorStoreException` | Vector Store 호출 실패 |
| RAG-LLM-001 | 503 | `LlmProviderExhaustedException` | 모든 LLM 프로바이더 차단 또는 소진 |
| RAG-LLM-003 | 500 | `LlmContextOverflowException` | 프롬프트가 LLM 컨텍스트 윈도우 초과 — `LlmProviderExhaustedException` 의 **하위 타입**이라 소진을 잡던 자리들이 그대로 잡는다(도달 경로가 같다). 503 이 아닌 이유는 결정적 실패여서 — 같은 요청을 다시 보내도 똑같이 실패하므로 고칠 것은 시간이 아니라 프롬프트 크기(`search-top-k`·`max-tokens`)나 서버 컨텍스트 설정이다 |
| RAG-RATE-001 | 429 | (RateLimitFilter) | API 요청 빈도 제한 초과 — `Retry-After` 헤더(초)에 대기 시간 포함 |
| RAG-INT-001 | 500 | (미분류 Exception) | 내부 알 수 없는 오류 |

## 응답 예시

```json
{
  "type": "about:blank",
  "title": "RAG-INDEX-001",
  "status": 500,
  "detail": "SHA-256 computation failed",
  "errorCode": "RAG-INDEX-001",
  "traceId": "a3f9b2c14d7e"
}
```

## traceId 사용 방법

- 요청 헤더 `X-Trace-Id`로 직접 traceId를 지정할 수 있습니다.
- 미지정 시 서버에서 12자리 랜덤 ID를 생성합니다.
- 응답 헤더 `X-Trace-Id`와 로그에서 동일 ID로 검색하면 해당 요청의 전체 흐름을 추적할 수 있습니다.

## 관련 파일

- `exception/RagException.java` — sealed 추상 기반 클래스
- `web/TraceIdFilter.java` — MDC traceId 주입 + X-Trace-Id 응답 헤더
- `controller/GlobalExceptionHandler.java` — 예외 → ProblemDetail 변환
