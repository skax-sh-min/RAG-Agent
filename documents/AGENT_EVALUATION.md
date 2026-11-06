# Tool 선택 사전 평가 — AGENT_MCP_DESIGN §7 Phase 3 (2026-11-06)

Phase 3(채팅 에이전트)의 **구현 전에** "Tool 선택을 네이티브 Tool Calling 으로 할지, 프롬프트 JSON 으로 할지"를 정하려고 쟀다. 설계서의 판단표([AGENT_MCP_DESIGN.md](AGENT_MCP_DESIGN.md) §7 Phase 3)를 이 결과로 채운다.

## 1. 한눈에

- **결정: 프롬프트 JSON 방식**(`ClassifierService` 와 같은 틀)으로 간다. 네이티브 Tool Calling 은 이 서버·모델 조합에서 쓸 수 없다.
- 프롬프트 JSON: 선택 정확도 **0.939**(46/49), 문서 질문의 잘못된 우회 **0/26**, 인자 유효 **46/46**, 형식 실패 **0/49**, 지연 p50 **0.8초**. 세 번 반복해도 같은 3건만 틀렸다.
- 네이티브(plan 프롬프트 + `tools`): 선택 정확도 **0.20**, 형식 실패 **0.59**. 프롬프트 없이 `tools` 만 주면 0.49, 형식 실패 0.22. 어느 쪽도 기준(0.90 / 5% / 5%)에 한참 못 미친다.
- 비교 대상 세 모델 중 **`gemma-4-E2B` 하나만 쟀다.** `gemma-4-e4b` 와 원격 `gemma-4-31b-it` 는 이 머신에서 띄워져 있지 않았다(§5).

## 2. 환경과 방법

| 항목 | 값 |
|---|---|
| 서버 | llama-server `b11960-8642541b2`, `localhost:1234`, `--jinja` 템플릿이 tool 지원으로 보고됨(`/props` 의 `supports_tool_calls=true`) |
| 모델 | `gemma-4-E2B-it-Q4_K_M.gguf` |
| 생각 | 끔(`chat_template_kwargs.enable_thinking=false`) — llama-server 는 이 필드를 지킨다. "서버 기본" 변형도 쟀다 |
| 요청 | 앱이 보낼 모양 그대로: 시스템 프롬프트 + 질문 한 줄, `temperature=0.2`, `max_tokens=256`, 네이티브는 `tools` + `parallel_tool_calls=false` |
| Tool | Q&A 에 보이는 5개: `searchKnowledge` · `findRelatedDocuments` · `queryCatalog`(1b 의 실제 `@Tool` 스키마) + `analyzeImpact` · `requestDocumentChange`(Phase 4 에서 만들 것 — 설계서 §3.3 의 이름·설명·인자로 하네스가 스텁으로 제시) |
| 세트 | 49건: 문서 26(기존 검색 골든셋의 질문) · 카탈로그 5 · 관련 문서 5 · 영향도 5 · 유지보수 5 · 잡담 3 (`agent-eval/tool-gold.json`) — 부록 A.3 의 구성과 같다 |
| 하네스 | `ToolCallingEvaluationTest`(기본 skip, `-Dtool-eval.enabled=true`). 앱 컨텍스트도 DB 도 쓰지 않고 HTTP 로 직접 부른다 |

**지표.** 선택 정확도 = 고른 Tool 이 기대와 같은 비율(기대가 `NONE` 이면 Tool 을 안 불러야 맞다). 잘못된 우회 = 문서 질문을 `searchKnowledge` 가 아닌 **다른 Tool** 로 보낸 비율(`NONE` 은 `RETRIEVAL` 로 내려가 같은 결과라 우회가 아니다). 인자 유효 = 호출한 건 중 JSON 이고 필수 인자가 있고 스키마에 없는 인자가 없는 비율. 형식 실패 = 본문에 `[TOOL_REQUEST]`·`<tool_call` 이 보이거나 잘려서 호출이 없거나, **호출을 구조화된 `tool_calls` 가 아니라 본문 글자로 쓴 것**(`searchKnowledge{query:…}`).

## 3. 결과

| 변형 | 정확도 | 문서 | 카탈로그 | 관련 | 영향도 | 유지보수 | 잡담 | 잘못된 우회 | 인자 유효 | 형식 실패 | 지연 p50 / p90 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 네이티브 · plan 프롬프트 · 생각 끔 | 0.204 | 0/26 | 0/5 | 3/5 | 2/5 | 2/5 | 3/3 | 0/26 | 7/7 | **0.592** | 0.6 / 1.8초 |
| 네이티브 · 프롬프트 없음 · 생각 끔 | 0.490 | 11/26 | 0/5 | 3/5 | 4/5 | 3/5 | 3/3 | 0/26 | 22/22 | 0.224 | 0.8 / 6.0초 |
| 네이티브 · plan 프롬프트 · 서버 기본 생각 | 0.245 | 1/26 | 1/5 | 3/5 | 2/5 | 2/5 | 3/3 | 0/26 | 10/10 | 0.531 | 0.6 / 1.8초 |
| **프롬프트 JSON** | **0.939** | **26/26** | 5/5 | 3/5 | 5/5 | 4/5 | 3/3 | **0/26** | **46/46** | **0.000** | **0.8 / 1.1초** |
| 프롬프트 JSON + `response_format=json_object` | 0.939 | 26/26 | 5/5 | 3/5 | 5/5 | 4/5 | 3/3 | 0/26 | 46/46 | 0.000 | 0.9 / 1.3초 |
| 프롬프트 JSON × 3 반복 | 0.939 · 0.939 · 0.939 | | | | | | | 0 | 전부 유효 | 0 | 0.8 / 1.2초 |

- **네이티브가 무너진 모양.** 모델은 호출하려 했다 — plan 프롬프트 변형에서 49턴 중 34턴. 그런데 27턴은 Gemma 4 의 호출 형식을 틀려 `searchKnowledge{query:<|"|>…<|"|>}` 같은 글자를 **본문으로** 냈고 서버 파서가 `tool_calls` 로 읽지 못했다. 구조화된 호출이 온 7건은 인자가 전부 유효했고 고른 Tool 도 맞았다. 즉 모델의 판단이 아니라 **출력 형식**이 문제다. 나머지는 "어떤 문서를 말씀하시는지 알려주세요" 같은 되묻기(Tool 호출 없음)다.
- **프롬프트 없이 `tools` 만 주면 더 낫다**(0.49). 시스템 프롬프트가 있을 때 오히려 형식이 더 깨졌다 — 이유는 확인하지 못했다.
- **프롬프트 JSON 의 오답 3건은 전부 안전한 쪽**이다: `rel-04`(트랜잭션 처리와 관련된 문서) · `rel-05`(EIMS 연계 내용이 있는 문서) 는 `findRelatedDocuments` 대신 `searchKnowledge`, `mnt-05`(PostgreSQL을 Oracle로 변경해)는 `requestDocumentChange` 대신 `searchKnowledge` 다. 둘 다 "확신이 없으면 searchKnowledge" 규칙으로 문서 검색으로 내려간다. 같은 질문이 부록 A.3 에서도 틀렸다(유지보수 "PostgreSQL을 Oracle로 변경해").
- `response_format=json_object` 는 이 세트에서 차이가 없다. 안 써도 형식 실패가 0 이라 쓰지 않는다(서버 의존 필드를 늘리지 않는다).

## 4. 판단표에 대입

| 설계서 판단표 | 이 결과 | 해당 |
|---|---|---|
| 선택 정확도 90% 이상 · 잘못된 우회 5% 이하 · 형식 실패 5% 이하 → **네이티브 Tool Calling** | 네이티브: 0.20 / 0 / 0.59 | 아니오 |
| 그 미만이지만 JSON 판정은 안정적 → **`ClassifierService` 와 같은 프롬프트 JSON** | JSON: 0.939 / 0 / 0, 세 번 반복 동일 | **예** |
| 둘 다 불안정 → 규칙 라우팅 + 상위 모델 | — | 해당 없음 |

**Phase 3 방식 = 프롬프트 JSON.** 설계서에서 바뀌는 곳: ① `ToolPlanService` 가 `tools` 를 싣지 않고 도구 목록을 프롬프트(`prompt.agent.plan`)에 적어 `{"tool":…,"arguments":{…}}` 를 받는다 — `OpenAiChatOptions.toolCallbacks`·`parallelToolCalls`·"서버가 `tools` 를 거부하면 미지원으로 기억"이 필요 없어지고, **계측 3곳(`CalibratingChatModel`·`LlmCurlLogger`·`ThinkingObservations`)이 Tool 메시지를 모르는 문제(§2.4)도 사라진다**. ② 파싱은 `BeanOutputConverter` 가 아니라 이 하네스의 방식(첫 `{` ~ 마지막 `}` 를 읽고 모르는 도구 이름이면 실패 → `RETRIEVAL`)이 출발점이다. ③ 인자 검증은 `ToolCatalog` 스키마로 한다.

## 5. 한계 — 이 결과가 말하지 못하는 것

- **모델은 하나뿐이다.** 설계서의 비교 대상은 `gemma-4-E2B` · `gemma-4-e4b` · 원격 `gemma-4-31b-it` 세 개인데 이 머신(`:1234`)에는 E2B 만 떠 있었다. 더 큰 모델에서는 네이티브가 쓸 만할 수 있다 — **재개 신호**: e4b 이상이 배포에 들어오면 같은 하네스로 `-Dtool-eval.base-url`/`-Dtool-eval.model` 만 바꿔 다시 잰다. 프롬프트 JSON 은 모델을 가리지 않으므로 그때도 안전한 기본이다.
- **네이티브의 결과는 서버 의존이다.** 부록 A.3 은 같은 E2B 로 LM Studio 에서 44/49(형식 실패 0)를 얻었다. 이번 llama-server b11960 에서는 0.20. 같은 모델·다른 서버의 파서/템플릿 처리 차이로 보이지만 **원인을 가르지는 않았다**(서버 로그·`--jinja` 인자 확인 안 함). 폐쇄망 배포의 서버가 무엇이든 프롬프트 JSON 은 같은 결과를 낸다는 점이 이 방식을 고르는 실질적인 이유다.
- **프롬프트를 이 세트를 보고 다듬었다.** 규칙 문장은 카테고리(문서 · 카탈로그 · 관련 · 영향도 · 유지보수 · 잡담)를 정의할 뿐 개별 질문을 싣지 않았지만, 같은 49건으로 방식을 고르고 점수를 냈으므로 낙관적일 수 있다. 질문 표현이 다른 세트(실제 사용자 질문 로그)로 Phase 3 완료 기준에서 다시 잰다.
- 표본이 작고(49건, 카테고리당 3~5건) 질문이 한 코퍼스(NEXCORE)에 몰려 있다. **관련 문서(rel) 유형은 3/5** 로 가장 약하다 — 안전한 오답이지만 이 Tool 의 가치를 줄인다.
- 지연은 생각을 끈 llama-server 값이다. LM Studio 처럼 끄지 못하는 서버에서는 부풀어 오른다(설계서 §2.4).
- 영향도·유지보수 Tool 은 스텁이라 *선택*만 쟀고 실행은 없다. 인자 의미(예: `requestDocumentChange` 의 `from`/`to` 가 맞게 채워졌는가)는 보지 않았다.

## 6. 재현

```bash
mvn -o test -Dtest=ToolCallingEvaluationTest -Dtool-eval.enabled=true \
    -Dtool-eval.label=e2b-json -Dtool-eval.mode=json \
    [-Dtool-eval.mode=native] [-Dtool-eval.prompt=none] [-Dtool-eval.thinking=server] [-Dtool-eval.json-format=true] \
    [-Dtool-eval.base-url=http://127.0.0.1:1234/v1] [-Dtool-eval.model=gemma-4-E2B] [-Dtool-eval.repeat=3]
```

케이스별 원자료는 `target/agent-eval/<label>/tool.jsonl`(고른 Tool · 인자 · 지연 · 본문 앞부분). 표의 집계는 이 파일을 세어 얻었다. `mvn clean` 이 `target/` 을 지우므로 숫자는 이 문서에 옮겨 두었다.
