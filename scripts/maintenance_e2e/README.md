# 유지보수 워크플로 종단 확인 (PLAN §6.33 4b)

실제 코퍼스의 **복사본**(`target/e2e-data`)으로 실제 앱을 띄워 영향도·유지보수 흐름을 HTTP 로 끝까지 확인한다. 실제 `data/` 는 읽기만 한다.

```bash
# 1) 복사본 만들기(SQLite 백업 API + converted MD) — 앱을 띄우기 전에
PYTHONIOENCODING=utf-8 python scripts/maintenance_e2e/prep.py

# 2) 복사본으로 앱 실행(.claude/launch.json 의 rag-agent-verify 와 같은 옵션, 포트 8098·DATA_DIR=./target/e2e-data)
mvn -o spring-boot:run "-Dspring-boot.run.jvmArguments=-DSERVER_PORT=8098 -DLLM_VERIFY_LOCAL_MODELS_ON_STARTUP=false -DDATA_DIR=./target/e2e-data -DAUTH_ENABLED=false -DAUTH_MANAGEMENT_ONLY=false -DAUTH_GUEST_IDENTITY=shared -DVECTORSTORE_TYPE=sqlite-vec -DSQLITE_VEC_EXTENSION_PATH=./lib/win64/vec0.dll -DEMBED_DIMENSIONS=1024 -DLLM_ROUTING_MODE=LOCAL_ONLY -DLOCAL_LLM_URL=http://127.0.0.1:1234/v1 -DLOCAL_LLM_MODEL=gemma-4-E2B -DEMBED_BASE_URL=http://127.0.0.1:28091/v1 -DEMBED_MODEL=bge-m3-Q4_K_M.gguf"

# 3) 시나리오(같은 앱에 순서대로)
PYTHONIOENCODING=utf-8 python scripts/maintenance_e2e/drive.py     # A: 요청 → 승인 전 불변 → 승인·적용 → 청크·벡터·FTS·MD 검증
PYTHONIOENCODING=utf-8 python scripts/maintenance_e2e/drive2.py    # B: 적용 전 청크가 바뀜(stale) · C: 거절 · D: 3글자 미만
PYTHONIOENCODING=utf-8 python scripts/maintenance_e2e/reindex.py   # E: 문서 재인덱싱 뒤에도 수정 유지(MD 되쓰기)
PYTHONIOENCODING=utf-8 python scripts/maintenance_e2e/control.py   # 대조: 건드리지 않은 문서의 재인덱싱 청크 수 변화
PYTHONIOENCODING=utf-8 python scripts/maintenance_e2e/semantic.py  # F: 의미 변경(실모델) — 가드가 거르는 것·통과한 것·MD_PARTIAL
PYTHONIOENCODING=utf-8 python scripts/maintenance_e2e/casevariants.py          # G: 대소문자 변형 끔/켬 비교 — 청크·FTS·MD 에 변형이 남는지
PYTHONIOENCODING=utf-8 python scripts/maintenance_e2e/casevariants_reindex.py  # G2: 변형 치환 뒤 재인덱싱해도 되살아나지 않는지(G 바로 다음에)
```

- 바꿀 말은 코퍼스에서 하나의 문서에만 2~5번 나오는 식별자(`BEDU2002` · `BEDU2001` · `TOT_CNT`)를 골랐다 — 다른 코퍼스에서는 `prep.py` 가 출력하는 후보로 `drive.py <옛말> <새말>` 을 쓴다(`drive2.py`·`reindex.py` 는 이 이름이 코드에 박혀 있다).
- 임베딩 서버(`28091`)와 로컬 LLM(`1234`)이 떠 있어야 한다. 스크립트는 앱을 시작·종료하지 않는다.
- **스크래치 DB 에만 쓴다.** 끝나면 앱을 내리고 `target/e2e-data` 를 지우면 된다.
