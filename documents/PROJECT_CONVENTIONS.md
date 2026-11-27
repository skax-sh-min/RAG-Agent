# 프로젝트 공통 규칙 (새 프로젝트용 참조)

> 이 문서는 RAG Agent 저장소에서 **실제로 쓰고 있고, 한 번 이상 깨져 본 뒤 굳은 규칙**을 다른 프로젝트에서
> 그대로 가져다 쓸 수 있게 일반화해 정리한 것이다. 도메인(RAG·LLM) 이야기는 빼고 **개발 환경 · 설계 ·
> 빌드 · 테스트 · 릴리즈 · 설치 · 업데이트 · 스크립트 작성 규칙**만 남겼다.
> 각 항목의 "예" 는 이 저장소에서 그 규칙이 구현된 자리다 — 새 프로젝트에서는 이름만 바꿔 옮기면 된다.
>
> 표기: **필수** = 어기면 실제로 깨진 적이 있다 · **권장** = 기본값으로 따르고, 바꾸려면 이유를 문서에 남긴다.

---

## 목차

1. [개발 환경](#1-개발-환경)
2. [저장소 구성과 문서 체계](#2-저장소-구성과-문서-체계)
3. [설계 규칙 (백엔드)](#3-설계-규칙-백엔드)
4. [설계 규칙 (UI)](#4-설계-규칙-ui)
5. [설정 · 환경변수](#5-설정--환경변수)
6. [빌드](#6-빌드)
7. [테스트](#7-테스트)
8. [실행 스크립트](#8-실행-스크립트)
9. [스크립트 작성 규칙 (공통)](#9-스크립트-작성-규칙-공통)
10. [릴리즈](#10-릴리즈)
11. [설치 · 업데이트 (런처)](#11-설치--업데이트-런처)
12. [Docker 배포](#12-docker-배포)
13. [Git · 커밋](#13-git--커밋)
14. [새 프로젝트 시작 체크리스트](#14-새-프로젝트-시작-체크리스트)

---

## 1. 개발 환경

| 항목 | 기준 | 비고 |
|---|---|---|
| 언어/런타임 | **Java 21** (가상 스레드 켬) | 빌드·릴리즈 스크립트가 `mvn -v` 로 Java 메이저 버전을 확인하고 21 미만이면 멈춘다 |
| 빌드 | **Maven 3.9+**, 저장소에 `mvnw`/`mvnw.cmd` 포함 | 래퍼 버전은 `.mvn/wrapper/maven-wrapper.properties` 에 고정. 스크립트는 `mvn` → 없으면 `./mvnw` 순으로 찾는다 |
| 프레임워크 | Spring Boot (부모 POM) | 버전은 `pom.xml` 한 곳이 진실 — 문서에 버전을 적으면 "정확한 값은 pom" 이라고 덧붙인다 |
| DB | SQLite (WAL, 커넥션 풀 1) + Flyway | 외부 DB 없이 단일 파일. 풀 1 은 SQLite 쓰기 직렬화 때문 |
| 개발 OS | Windows 11 + Git for Windows(Git Bash) 가 주, macOS/Linux 지원 | Windows 에서도 `.sh` 를 Git Bash 로 돌릴 수 있어야 한다 |
| 셸 스크립트 런타임 | **POSIX sh + cmd.exe 만** | PowerShell(`.ps1`)·Python 을 운영 스크립트의 필수 의존으로 만들지 않는다(§9.1) |
| 릴리즈 도구 | git, GitHub CLI(`gh`), `unzip`(또는 JDK `jar`) | CI(GitHub Actions)를 쓰지 않고 **로컬에서** 릴리즈한다(§10) |
| 로컬 미리보기 | `.claude/launch.json` 에 실행 구성(`mvn spring-boot:run` + `-D` 인자) | 포트를 구성마다 다르게, 검증용 구성은 임시 `DATA_DIR` 을 쓴다 |

**필수 — 줄바꿈과 인코딩**

- `.gitattributes` 로 줄바꿈을 파일 종류별로 고정한다. `core.autocrlf=input` 은 체크아웃 때 CRLF 를 되살리지 않아
  `.cmd` 가 LF 로 돌아오고, cmd.exe 는 바이트 오프셋으로 파싱하므로 주석 조각이 명령으로 실행된다.

  ```gitattributes
  *.bat text eol=crlf
  *.cmd text eol=crlf
  *.ps1 text eol=crlf
  *.sh  text eol=lf
  ```
- 소스·문서는 UTF-8, LF. Java 18+ 의 `file.encoding` 은 UTF-8 이지만 **콘솔 출력 인코딩은 따로**다 —
  Windows 는 `chcp 65001` + `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`, sh 는 `LANG` 이 비었을 때 `ko_KR.UTF-8` 로 채운다(§8).
- Git Bash 의 `sed`·`awk` 는 CRLF 의 CR 을 **조용히 버린다** — 파일을 고치는 스크립트는 `sed -b` 를 쓴다(§9.3).

**필수 — 비밀 보호**

- `.env` 는 `.gitignore` 대상이고, 커밋을 막는 pre-commit 훅을 저장소에 둔다(`scripts/hooks/pre-commit` + `scripts/install-hooks.sh`, 클론 후 1회 `sh scripts/install-hooks.sh`).
- 저장소에는 값이 빈 `.env.example`(변형이 있으면 `.env.example.<변형>`)만 둔다.

---

## 2. 저장소 구성과 문서 체계

```
pom.xml / mvnw / mvnw.cmd / .mvn/
.env.example(.xxx)       ← 설정 견본 (값 비움)
.gitattributes / .gitignore
Dockerfile / docker-compose.yml
LICENSE / NOTICE
README.md / README.kr.md ← 영어·한국어 두 벌
CLAUDE.md                ← 에이전트·개발자용 프로젝트 가이드 (자주 안 바뀌는 것만)
documents/               ← 상세 문서
scripts/                 ← 실행·릴리즈·평가 스크립트, hooks/
src/main/java, src/main/resources (application.properties, messages*.properties, db/migration, templates, static)
src/test/...
data/                    ← 런타임 데이터 (gitignore)
```

**문서 역할 분리 (권장)**

| 문서 | 담는 것 | 규칙 |
|---|---|---|
| `CLAUDE.md` | 스택·구조·파일 역할 한 줄·규약·하드 제약 | **자주 바뀌지 않는 것만**. 근거·사고 기록은 링크(`↗`)로 PITFALLS 에 보낸다 |
| `documents/PITFALLS.md` | 각 규칙의 결정 근거 · 함정 · 실패 기록 | "불필요해 보이는 코드가 왜 거기 있는지" 를 설명하는 곳. 해당 파일을 고치기 전에 읽는다 |
| `documents/PLAN.md` | 개발 계획(마스터) | 대시보드 먼저, 완료 항목은 제자리에서 요약, **섹션 번호는 바꾸지 않는다**(소스 주석이 `§6.29` 처럼 인용한다) |
| `documents/DATABASE.md` | 테이블·컬럼·메타데이터 키 | 스키마를 바꾸면 같은 커밋에서 고친다 |
| `documents/OPERATOR_MANUAL.md` | 설치·환경변수·실행·백업·업데이트·릴리즈 절차 | 운영자가 그대로 따라 할 수 있는 명령 단위 |
| `documents/ADMIN_MANUAL.md` / `USER_MANUAL.md` | 화면 사용법 | |
| `documents/*_EVALUATION.md` | 기본값·상수를 **측정으로 정한 결과**(환경·방법·표·재현 방법) | 값을 바꾸려면 같은 하네스로 다시 재고 문서도 고친다 |
| `documents/UI.md` / `ERROR_CODES.md` | UI 구조 · 오류 코드 표 | |

- 기본값을 바꾸면 그 값이 복제된 곳(코드 상수·`application.properties`·`.env.example*`·README 두 벌·매뉴얼 표·테스트)을 **전부 grep 해서** 함께 고친다. 측정 기록·사고 기록의 옛 숫자는 고치지 말고 "이후 바뀌었다" 메모를 단다.
- 큰 문서를 일괄 편집한 뒤에는 `git diff HEAD -- <파일> | grep -E "^[-+]#{2,4} "` 로 **헤더가 의도치 않게 사라지지 않았는지** 확인한다.

---

## 3. 설계 규칙 (백엔드)

### 3.1 데이터와 상태

- **불변 레코드가 기본**(필수 권장): 요청/응답·상태·설정 값은 `record`. 상태 변경은 `state.toBuilder().x(..).build()` 로 새 인스턴스를 만든다(`withXxx()` 류 개별 세터는 두지 않는다).
- 레코드에 컴포넌트를 추가하면 위치 기반 생성자를 쓰는 팩토리(`of()`)도 함께 고친다.
- **"모름" 은 값으로 표현한다** — 0·기본값으로 위장하지 않는다. 측정 안 된 값은 `null`(nullable 필드), 모르는 설정은 항목 자체가 없다. 추측한 숫자로 다음 계산을 하지 않게 하기 위해서다.
- 판정을 못 읽으면 "통과" 가 아니라 "판정 없음" 이다(실패를 성공으로 위조하지 않는다).

### 3.2 단일 출처 (Single Source of Truth)

- 같은 규칙·숫자를 두 곳에서 계산하지 않는다. 미리보기·화면·문서용 숫자도 **런타임 함수를 그대로 부른다** — 따로 계산하면 둘이 갈라져도 아무 테스트도 깨지지 않는다.
- 버전·외부 바이너리 버전은 `pom.xml` 속성 **한 곳**에만 두고, 빌드가 리소스 파일(`VERSION`, `build-info.properties`)로 내려 코드가 읽는다. 코드에 버전 문자열을 쓰지 않는다.
- 상수 키(메타데이터 키·설정 키)는 상수 클래스로만 접근한다 — 문자열 리터럴 금지.
- 스키마는 **마이그레이션 파일에만** 있다(Flyway). 저장소 클래스에 `CREATE TABLE IF NOT EXISTS`/`ALTER TABLE` 을 넣지 않는다. 이미 적용된 마이그레이션은 고치지 않고 새 버전을 더한다(재생성 가능한 검색 색인만 예외).

### 3.3 규약은 테스트로 강제한다 (필수 권장)

문서에만 적은 규약은 언젠가 깨진다. 지켜야 하는 규칙은 **소스를 읽는 "컨벤션 테스트"** 로 빌드에서 막는다. 이 저장소의 예:

| 테스트 | 막는 것 |
|---|---|
| `SpringBeanConstructorConventionTest` | 생성자가 둘 이상인 빈에 `@Autowired` 누락(단위 테스트는 통과하고 앱만 안 뜬다) |
| `ParallelIsolationConventionTest` | 전역 상태를 건드리는 테스트에 `@ResourceLock` 누락 |
| `TimeZoneConventionTest` | 존 없는 `now()`·`ZoneId.systemDefault()`·zone 없는 cron |
| `MessageBundleConventionTest` | 메시지 번들 키 모양·한/영 키 집합 불일치·여러 줄 값의 `\n\` 누락 |
| `AppPropertiesSafeAccessorTest` | null-safe 접근자 대신 raw getter 사용 |
| `LauncherProtocolConventionTest` | pom 의 호환 세대 값과 코드 상수 불일치 |

새 프로젝트에서도 "이걸 어기면 조용히 깨진다" 싶은 규칙이 생기면 그날 컨벤션 테스트를 같이 만든다.

### 3.4 실패와 기본값

- **실패는 기능 단위로 degrade** 한다: 부가 기능(진단·알림·자동 생성)의 실패가 본 기능의 응답을 막지 않는다 — 예외를 삼키고 "없음" 으로 진행하되 로그를 남긴다.
- **설정 오류는 기동을 막지 않는 것이 기본**: 잘못된 값이면 ERROR 로그 + 화면에 사유를 보이고 **그 기능만** 끈다. 고치는 화면에 닿을 수 있어야 하기 때문이다. 예외는 보안 설정(키 길이 미달 등) — 그건 기동 실패가 맞다.
- 외부로 나가는 기능(업데이트 확인 등)은 **기본 꺼짐**. 꺼져 있으면 밖으로 아무 요청도 나가지 않는다(폐쇄망 전제).
- 위험한 순서는 "검증 → 백업 → 변경" 이고, 백업이 실패하면 **아무것도 바꾸지 않고 멈춘다**. 데이터를 지우는 동작은 새 데이터가 준비된 **뒤에** 한다(예: 임베딩 성공 뒤 옛 행 삭제).
- 상태 전이는 compare-and-set(`UPDATE … WHERE status = ?`) 하나의 메서드로만 한다.

### 3.5 구성 · 의존성 주입

- `JdbcTemplate` 로 직접 SQL(SQLite 와 JPA 비호환). Spring Data JPA 를 쓰지 않는다.
- 설정은 `@ConfigurationProperties` 레코드 + `xxxSafe()` null-guard 접근자로만 읽는다.
- 생성자가 여러 개인 빈은 주입할 생성자에 `@Autowired`. 생성자에 협력자를 계속 얹어 테스트가 흔들리면 **세터 주입**(`@Autowired(required=false)`)으로 선택 협력자를 받는다.
- 쓰지 않는 자동 구성(auto-config)은 `spring.autoconfigure.exclude` 로 명시적으로 뺀다 — 빈 설정값 하나로 기동이 죽는 일을 막는다.
- 외부 호출은 데코레이터 체인(상한·사용량 집계·차단·재시도)으로 감싸고, 체인을 우회하는 경로는 **한 곳**에만 허용한다(컨벤션 테스트로 막는다).

### 3.6 시간 · 로그 · 관측

- **DB 에 쓰는 시각은 UTC**(`yyyy-MM-dd HH:mm:ss`). 사람이 읽는 서버 시계(로그·cron·백업 파일명)는 설정 하나(`app.time-zone`, 기본 `Asia/Seoul`)로 정하고 JVM 기본 시간대는 어디서도 읽지 않는다.
- **화면의 시각은 브라우저가 바꾼다**: 서버는 `<time datetime="…Z">` / ISO UTC 로 내보내고 JS 가 보는 사람의 시간대로 그린다.
- 요청마다 `traceId` 를 MDC 에 넣고 `X-Trace-Id` 로 돌려준다. **새 실행기·가상 스레드를 열 때는 MDC 를 전파하는 래퍼로 감싼다** — 감싸지 않은 곳의 로그만 `[-]` 로 남는다. 클라이언트가 준 id 는 모양(영숫자·`_-`, 64자 이하)일 때만 받는다(로그 주입 방지).
- 감사 로그는 별도 appender(롤링 파일). 본문·비밀은 남기지 않는다. 키는 앞뒤 3글자만(`abc***xyz`).

### 3.7 보안 기본값

- 인증 모드가 여러 개면 모드별 `SecurityFilterChain` 을 분기하고, **관리 경로는 언제나 `hasRole("ADMIN")`** — `.authenticated()` 는 가입만 하면 통과한다.
- 업로드는 매직바이트 검증(422) · 크기 상한(413) · 저장 용량 상한(413)을 **쓰기 전에** 확인한다.
- 레이트 리밋 버킷 키는 "진짜 사용자 하나" 일 때만 userId, 아니면 IP. 클라이언트 IP 판정은 클래스 하나(`ClientIpResolver`)가 맡고 `X-Forwarded-For` 는 설정으로 신뢰할 때만 읽는다.
- 신뢰 경계 밖 텍스트(업로드 문서·LLM 출력·사용자 입력)를 HTML 로 렌더할 때는 살균기(DOMPurify)가 **있을 때만** 렌더하고 없으면 평문(§4).
- 오류 응답은 RFC 9457 `ProblemDetail` + 앱 오류 코드(`documents/ERROR_CODES.md`).

---

## 4. 설계 규칙 (UI)

| 항목 | 규칙 |
|---|---|
| 스택 | **서버 렌더링**: Thymeleaf + Layout Dialect + **HTMX**, Bootstrap 5 + Bootstrap Icons. **JS 프레임워크 없음** |
| 자산 | 프런트 라이브러리는 **WebJars 로 번들**(CDN 금지 — 폐쇄망). `webjars-locator-lite` 로 버전 없는 `/webjars/…` URL. 정적 자산은 내용 해시 URL(`spring.web.resources.chain.strategy.content`) |
| HTMX | 엔드포인트가 `"fragments/xxx :: selector"` 조각을 돌려준다. 폼 검증 실패는 400 이 아니라 사유를 담은 폼을 `HX-Retarget` 으로 돌려준다(400 은 "요청 실패" 토스트만 남긴다) |
| 로직 위치 | **JS 테스트 하네스가 없으므로 규칙·계산은 서버에 둔다** — diff 강조·문자열 분해·마커 편집 같은 것도 서버가 계산하고 화면은 그리기만 한다 |
| Thymeleaf JS 인라인 | `/*[[${x}]]*/ default` 를 쓰는 `<script>` 에는 **반드시** `th:inline="javascript"`. 없으면 오류 없이 기본값으로만 돈다. 새 스크립트 블록을 넣을 때 `/*[[` 를 grep |
| 마크다운 렌더 | `marked` + `DOMPurify` **둘 다** 로드됐을 때만 `innerHTML`, 하나라도 없으면 `textContent`. 렌더 지점을 새로 만들면 이 게이트를 같이 가져간다 |
| 글자 크기 | 기준 CSS 변수 하나(`--chat-font-size`) + 내부는 전부 `em` 비율 단계 클래스. 인라인 `font-size` 금지 |
| 반응형 | Bootstrap 브레이크포인트: ≥md 고정 사이드바, <md offcanvas 드로어. 전체 높이는 `100dvh`, 메시지 영역만 스크롤. 표는 `.table-responsive` |
| 테마 | 라이트/다크 토글(`data-theme` + `localStorage`). 화면 개인 설정은 브라우저 `localStorage` 에만 |
| 다국어 | `messages.properties`(영) + `messages_ko.properties`(한). 키 집합이 같아야 하고, 여러 줄 값은 줄 끝마다 `\n\`. 서비스 테스트는 목이 아니라 **실제 번들**로 확인 |
| 권한별 화면 | 관리자 전용 정보(버전·키·내부 설정)는 모델에서 **빼고** 렌더한다(숨김 CSS 아님) |
| 폴링 | 가벼운 상태 표시기만 짧게(수 초), 배지류는 60초. 폴링 대상은 서버에 저장된 결과를 읽기만 하고 외부 호출을 늘리지 않는다 |
| 접근성 | 아이콘 버튼에 `aria-label`, 키보드로 닫히는 드로어·모달 |

---

## 5. 설정 · 환경변수

- **우선순위**: 환경변수/`.env` → `application.properties` 기본값. 기본값은 `application.properties` **한 곳**에만 적고, Compose 의 `environment:` 에 다시 적지 않는다(`env_file` 로 `.env` 를 통째로 넘긴다).
- 설정 키 이름: 프로퍼티 `app.xxx.yyy` ↔ 환경변수 `XXX_YYY`. `.env.example` 에 전부 문서화한다.
- 값을 **핫(즉시 반영)** 과 **재기동 필요** 로 나누고, 화면에 "재기동 필요" 배지는 고칠 수 있는데 재기동해야 반영되는 칸에만 단다. 핫 소비자는 매 호출 접근자를 다시 읽는다(필드 캐시 금지).
- 런타임 오버라이드는 DB 테이블에 저장하고 메모리 캐시로 읽는다(읽기 핫패스에서 DB 를 치지 않는다).
- 기본 데이터 폴더 `DATA_DIR`(기본 `./data`) 아래에 DB·백업·업데이트·네이티브 추출본을 모은다.
- SQLite 세션 PRAGMA 는 **JDBC URL 파라미터로만**(`connection-init-sql` 은 한 문장만 실행돼 조용히 무효). 진짜 커넥션을 열어 값을 되묻는 테스트를 둔다.

---

## 6. 빌드

```bash
mvn clean package                 # 테스트 포함 (long-running 제외)
mvn clean package -DskipTests     # 테스트 생략
mvn clean package -Pall-tests     # 오래 걸리는 테스트까지 전부 (릴리즈가 이것)
```

**산출물 규칙**

| 파일 | 내용 |
|---|---|
| `target/<artifact>-<ver>.jar` | Spring Boot 실행 jar. `spring-boot-maven-plugin` 의 `build-info` 목표로 `META-INF/build-info.properties`(버전·빌드 시각) 포함 |
| `target/<artifact>-<ver>-launcher.jar` | `maven-jar-plugin` 두 번째 실행(`classifier=launcher`, `includes` 로 런처 패키지만)이 만드는 작은 런처(§11) |

- 앱 jar 매니페스트에 `Min-Launcher-Protocol`(pom 속성 `min.launcher.protocol`)을 싣는다 — 앱↔런처 호환 세대.
- **외부 네이티브 바이너리를 jar 에 넣을 때**(예: sqlite-vec): 버전·플랫폼별 SHA-256 을 pom 속성으로 두고, 빌드(`maven-antrun-plugin`, `generate-resources`)가 받아 `~/.m2/<도구>/<버전>/` 에 캐시 → **해시 불일치면 빌드 실패** → `target/classes/native/<도구>/<플랫폼>/` 로 푼다. `-D<도구>.skip=true` 로 네트워크 없이 빌드하는 탈출구를 둔다. 출력은 `<resources>` 로 선언하지 말고 `target/classes` 에 바로 쓴다(부모 POM 의 리소스 필터링을 덮어쓰지 않게).
- 런타임은 jar 에서 현재 플랫폼 파일을 `{DATA_DIR}/native/<도구>-<버전>/` 로 꺼내 쓰고(버전 폴더라 롤백한 옛 앱과 충돌하지 않는다), 환경변수로 지정한 경로가 있으면 그것이 이긴다.
- 커버리지 게이트(JaCoCo `check`, `verify` 단계)는 패키지별 하한을 두고 TODO 로 상향 계획을 적는다.
- 시그니처를 바꾼 뒤 증분 컴파일이 옛 테스트를 다시 컴파일하지 않을 수 있다 — 의심되면 `target/classes`·`target/test-classes` 를 지우고 다시.

---

## 7. 테스트

| 규칙 | 내용 |
|---|---|
| 병렬 실행 | `src/test/resources/junit-platform.properties` 로 **클래스 단위 병렬**, 메서드는 직렬. Spring 컨텍스트·Logback appender·전역 static 을 건드리는 테스트는 `@ResourceLock("global-state")`(컨벤션 테스트가 강제) |
| 오래 걸리는 테스트 | 실제 프로세스를 수십 번 띄워 평소 빌드를 **혼자 몇 분씩** 늘리는 클래스만 `@Tag("long-running")`. surefire `excludedGroups=${test.excludedGroups}` 로 평소 제외, 프로파일 `all-tests`(전부)·`long-running-tests`(그것만). 릴리즈 빌드는 `-Pall-tests`. 그 대상 파일(스크립트·런처)을 고쳤으면 `mvn test -Plong-running-tests` 로 따로 돌린다 |
| 스크립트도 테스트한다 | 릴리즈·실행 스크립트는 **실제로 돌리는** 하네스로 테스트한다: 임시 저장소 + 로컬 bare 원격 + 가짜 `mvn`·`gh`(환경변수 `RELEASE_MVN`/`RELEASE_GH` 로 주입). `.cmd` 래퍼도 따로 테스트 |
| DB 테스트 | 손으로 만든 축소 스키마 금지 — 앱과 **같은 마이그레이션을 적용한** 임시 DB(`SqliteTestDatabase.open()`) |
| Web 테스트 | `@WebMvcTest` 에는 `spring-boot-starter-webmvc-test`·`security-test` 의존성(없으면 보안이 안 걸려 전부 302) |
| 결과 확인 | `mvn -q test` 는 성공 시 아무것도 안 찍는다 — 합계는 `target/surefire-reports/TEST-*.xml` 에서 |
| 측정형 테스트 | 기본값을 정하는 평가 하네스는 일반 테스트와 분리(시스템 프로퍼티로 켤 때만)하고 결과를 `*_EVALUATION.md` 에 남긴다 |

---

## 8. 실행 스크립트

플랫폼 × 실행 방식마다 스크립트 하나. 이름 규칙: `<플랫폼>_<동작>_by_<방식>.<확장자>` 또는 플랫폼 공통이면 `<동작>_by_<방식>.sh|.cmd` 짝.

| 스크립트 | 하는 일 |
|---|---|
| `run_by_mvn.sh` / `.cmd` | `.env` 로드 → `mvn spring-boot:run` (소스 실행) |
| `run_by_jar.sh` / `.cmd` | `.env` 로드 → (옵션) 빌드 → 최신 jar 를 `data/<app>.jar` 로 **복사한 뒤 그것을** 실행 |
| `ubuntu_run_by_docker.sh` · `macos_run_by_apple_container.sh` (+ `_stop_`, `_reset_data_`) | 컨테이너 의존 서비스 기동·정지·데이터 초기화 |
| `release.sh` / `release_windows.cmd` | §10 |
| `install-hooks.sh` | git 훅 연결 |

**jar 실행 스크립트의 규칙**

- 실행 파일을 고정 경로(`data/<app>.jar`)로 **복사해서** 띄운다: 실행 중에도 `mvn clean` 으로 `target/` 을 지울 수 있고, 버전이 바뀌어도 실행 명령·서비스 등록이 같다.
- 빌드 직후에는 `target/maven-archiver/pom.properties` 의 `version=` 으로 **방금 만든 jar** 를 정확히 고른다(타임스탬프로 고르지 않는다). 빌드 없이 실행할 때만 최신 파일을 고르되 `-launcher.jar` 는 제외한다.
- 빌드 산출물이 없지만 예전 복사본이 있으면 그것으로 실행하되 "최신이 아님" 을 알리고 3초 기다린다.
- 실행 포트는 스크립트가 `.env` 위에 덮어쓴다(개발 기본 8081, 바꾸려면 그 줄을 고친다).
- 외부 의존(예: 별도 벡터 DB 서버)을 띄우지 않는 스크립트는 필요한 설정일 때 **경고**를 찍는다.

---

## 9. 스크립트 작성 규칙 (공통)

### 9.1 언어 선택

- **Linux/macOS: POSIX `sh`**(`#!/bin/sh`, bash 전용 문법 금지). **Windows: `.cmd`/`.bat`**. **`.ps1` 은 새로 만들지 않는다.** Python 을 운영 스크립트의 필수 의존으로 만들지 않는다(평가·분석 스크립트는 예외).
- 로직이 긴 스크립트는 **`.sh` 하나에만 구현**하고 Windows 판은 **Git for Windows 의 bash 를 찾아 그 `.sh` 를 실행하는 래퍼**로 만든다(`release_windows.cmd`). 탐색 순서: 전용 환경변수(`RELEASE_BASH`) → `where git` 옆의 `..\bin\bash.exe` → `%ProgramFiles%\Git\bin\bash.exe` → `%ProgramFiles(x86)%…` → `%LocalAppData%\Programs\Git\bin\bash.exe`. **WSL 의 `System32\bash.exe` 는 쓰지 않는다**(Windows 경로를 못 읽는다). 스크립트 경로는 `\` → `/` 로 바꿔 넘긴다.
- 짧은 실행 스크립트(`run_by_*`)는 `.sh`·`.cmd` 를 각각 쓰되 **옵션·동작·메시지 의미를 맞춘다**.

### 9.2 공통 옵션 규약

| 옵션 | 뜻 | 비고 |
|---|---|---|
| `-h`, `--help` | 도움말 출력 후 종료 코드 0 | `.env`·Maven 없이도 동작해야 한다 → **인자 해석을 환경 점검보다 먼저** |
| `-b`, `--build` | `mvn package` 후 실행 | `-cb` 와 배타 |
| `-cb`, `--clean-build` | `mvn clean package` 후 실행 | |
| `-st`, `--skip-test` | 빌드에 `-DskipTests` | 옛 이름 `--skip-tests` 도 받는다. 기본은 **테스트 수행** |
| `--build-only` | 빌드·검증만, git·배포 무변경 | 릴리즈 계열 |
| `--no-publish` | 로컬 단계만 하고 원격 명령은 **출력만** | |
| `--pr` | 보호 브랜치용 — 릴리즈 브랜치 + PR | |
| `--next X.Y.Z-SNAPSHOT` | 다음 개발 버전 | 기본 patch+1 |
| `--notes-file F` | 릴리즈 노트 파일 | 기본 자동 생성 |
| `--any-branch` | 기준 브랜치 외에서 허용 | |
| `-y`, `--yes` | **받지 않는다** | 되돌리기 어려운 동작의 확인은 건너뛸 수 없다 — 넘기면 명시적으로 거절 |

옵션 해석 규칙(필수):

- 알 수 없는 옵션 → stderr 에 `오류: … --help 로 사용법을 확인하세요` + **종료 코드 1**. 조용히 무시하지 않는다.
- 같은 옵션 두 번·배타 옵션 동시 지정 → 오류.
- **다른 옵션 없이는 뜻이 없는 옵션**(`-st` 만 단독) → 무시하지 말고 오류로 알린다.
- 위치 인자(버전 등)는 하나만, 둘 이상이면 오류.
- 도움말은 스크립트 머리 주석을 그대로 출력해 두 벌 관리를 피한다(`sed -n '2,/^POM=/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'`).
- 사용 예를 도움말 끝에 한 줄(`예: run_by_jar.sh -cb -st`).

### 9.3 POSIX sh 작성 규칙

```sh
#!/bin/sh
set -e                                   # 단, 실패를 직접 다루는 긴 스크립트는 die() 로 명시 처리
SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR/.."                      # 어디서 실행해도 프로젝트 루트로
```

- 오류·정보 출력 함수: `die() { echo "<name>: ERROR: $*" >&2; exit 1; }` / `info() { echo "<name>: $*"; }`.
- `.env` 로드: `set -a; . "./.env"; set +a` — 값에 공백이 있으면 따옴표 필수. `.env` 가 없으면 `cp .env.example .env` 안내와 함께 **실패**한다(조용히 끝나지 않는다).
- 비대화형 셸에서 한글이 깨지지 않게 `LANG`/`LC_ALL` 이 비었으면 `LANG=ko_KR.UTF-8`.
- 필요한 도구는 `command -v mvn >/dev/null 2>&1 || die "…설치 안내…"`.
- 비었을 수 있는 인자 묶음은 일부러 따옴표 없이 펼친다(빈 인자를 넘기지 않게) — 그 줄에 이유를 주석으로.
- **Git Bash 대응**: 파일을 고치는 `sed` 는 `if printf x | sed -b p >/dev/null 2>&1; then SED="sed -b"; else SED="sed"; fi`(BSD sed 는 `-b` 가 없고 필요 없다). 읽은 값은 `tr -d '\r'`.
- 해시는 stdin 으로: `sha256sum < "$f" | cut -d' ' -f1`(파일명을 주면 `\` 가 든 Windows 경로에서 앞에 `\` 가 붙는다), 없으면 `shasum -a 256`, 그다음 `openssl dgst -sha256`.
- jar 내부 읽기: `unzip -p jar entry`, 없으면 임시 폴더에 `jar xf`.
- 파일 수정은 임시 파일 → `cat tmp > 원본`(권한·줄바꿈 유지) → **수정 결과를 다시 읽어 검증**.
- 상태를 바꾸는 스크립트는 `trap 'restore' EXIT` / `trap 'restore; exit 130' INT TERM` 으로 중단 시 원복.
- 확인 질문: `printf '%s [y/N] '; read -r answer || answer=""` — `y`/`yes` 외, **입력 없음(EOF)도 "아니오"**.
- 함수만 불러오는 모드를 둔다: 마지막에 `if [ -z "${LIB_ONLY:-}" ]; then main "$@"; fi` → 테스트가 `LIB_ONLY=1 . script.sh` 로 함수 단위 검증.
- 외부 명령은 환경변수로 바꿀 수 있게(`MVN="${RELEASE_MVN:-mvn}"`, `GH="${RELEASE_GH:-gh}"`) — 테스트가 가짜 도구를 끼운다.

### 9.4 cmd(.cmd/.bat) 작성 규칙

```bat
@echo off
REM ASCII ONLY - do not add Korean text to this file. ...
chcp 65001 >nul
setlocal enabledelayedexpansion
...
cd /d "%~dp0.."
```

- **ASCII 전용(필수)**: 주석·메시지 모두 영어. cmd.exe 는 바이트 오프셋으로 파일을 읽어 DBCS 문자가 있으면 오프셋이 밀려 주석 조각이 실행된다(파일 인코딩과 코드페이지가 같아도). 파일 맨 위에 이 경고 주석을 남긴다.
- 줄바꿈 CRLF(`.gitattributes`).
- 더블클릭으로도 동작: `cd /d "%~dp0.."` 로 루트 이동, 끝에 `pause`(대화형 실행 스크립트만 — 릴리즈 래퍼처럼 다른 도구가 부르는 것은 `pause` 금지).
- 종료 코드 전달: 끝에서 `endlocal & exit /b %EXIT_CODE%`, 실패 경로는 `set "EXIT_CODE=1"` 후 `goto :end`.
- 인자 해석은 `:parse_args` 루프 + `if /i "%~1"=="-b" goto :opt_build` + `shift /1`(`%0` 을 보존해 `%~dp0` 이 계속 유효하게).
- 괄호 블록 안에서 바뀌는 변수는 `!VAR!`(지연 확장).
- `mvn` 은 `mvn.cmd` 라 반드시 `call mvn …` — 없으면 Maven 이 끝날 때 스크립트도 끝난다. 호출 뒤 `if errorlevel 1`.
- `.env` 로드: `for /f "usebackq tokens=1,* delims==" %%A in (`findstr /v "^#" .env`) do SET %%A=%%B`.
- 경로가 들어가는 곳은 괄호 블록으로 감싸지 않는다(`C:\Program Files (x86)\…` 의 `)` 가 블록을 끝낸다) — `goto` 로 흐름을 짠다.
- `copy` 는 대상 폴더를 만들지 않는다 → `if not exist dir mkdir dir`. 복사 실패는 "실행 중인 앱이 파일을 잠갔을 수 있음" 으로 안내.
- 대기: `timeout /t 3 /nobreak >nul 2>&1 || ping -n 4 127.0.0.1 >nul 2>&1`(stdin 리다이렉트 시 timeout 실패 대비).
- `java` 의 `-D` 는 `-jar` **앞에**: `java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar app.jar`.
- 오류 메시지는 `echo ERROR: … 1>&2`.

### 9.5 메시지 · 실패 처리 원칙

- 실패 메시지는 **무엇이 됐고, 무엇이 안 됐고, 다음에 칠 명령이 무엇인지**를 말한다(예: "브랜치·태그는 푸시됨; 다시: `gh release create …`").
- 아무것도 바꾸지 않은 실패에는 "(nothing changed)" 를 붙인다.
- 비용이 드는 단계(빌드 수 분) **전에** 확인하고, 되돌리기 어려운 단계(커밋·푸시·게시) **직전에** 한 번 더 확인한다.
- 사전 점검 → 빌드 → 검증 → 로컬 변경 → 원격 변경 순서. 앞 단계 실패는 흔적을 남기지 않는다.

---

## 10. 릴리즈

**원칙**: 릴리즈는 **로컬 PC 에서 스크립트로** 만든다(CI 비용 없음, 비밀이 GitHub 에 올라가지 않음). 앱이 보는 것은 GitHub Release 의 **태그 · jar · `.sha256`** 뿐이다.

| 환경 | 명령 |
|---|---|
| Linux · macOS · Git Bash | `sh scripts/release.sh [X.Y.Z] [옵션]` |
| Windows | `scripts\release_windows.cmd [X.Y.Z] [옵션]` (Git Bash 로 같은 `release.sh` 실행) |

**단계**

1. **사전 점검** — 깨끗한 작업 트리(새 파일 포함 — jar 에 섞인다) · 기준 브랜치(`main`, `RELEASE_BRANCH`) · 원격보다 뒤처지지 않음 · 태그가 로컬·원격에 없음 · Maven 이 Java 21 · `gh auth status`. 여기까지 아무것도 안 바뀐다. → 「릴리즈할까?」「버전 맞나?」 두 질문(`--build-only` 는 생략).
2. **빌드** — pom 버전을 릴리즈 버전으로 → `mvn -B clean package -Pall-tests`(`-st` 면 `-DskipTests`). 실패 시 pom 원복.
3. **검증·산출물** — jar 의 `build.version` = 릴리즈 버전, `Start-Class` = 앱, `Min-Launcher-Protocol` = 양의 정수(1 초과면 릴리즈 노트에 "런처 교체 필요" 안내), 런처 jar 의 `Main-Class`. `target/release/` 에 네 파일:
   - `<app>-X.Y.Z.jar` + `<app>-X.Y.Z.jar.sha256`(`sha256sum` 형식 `<해시>  <파일명>`)
   - `<app>-X.Y.Z-launcher.jar` + `.sha256`
4. **git** — 마지막 확인 → `release: vX.Y.Z` 커밋 → 주석 태그 `vX.Y.Z` → pom 을 다음 `-SNAPSHOT` 으로 올려 `chore: begin X.Y.(Z+1)-SNAPSHOT` 커밋(안 올리면 이후 빌드가 계속 그 버전이라 자처해 업데이트 알림이 뜬다).
5. **게시** — 브랜치·태그 푸시 → **draft** 릴리즈에 네 파일 업로드(`gh release create … --verify-tag --draft`) → 업로드가 끝나면 `gh release edit vX.Y.Z --draft=false`(반쯤 올라간 릴리즈가 보이지 않게).

**`--pr` (main 이 PR 로만 받을 때)**: 커밋·태그를 `release/X.Y.Z` 브랜치에 만들고(시작 브랜치 무변경) 브랜치·태그 푸시 → draft 릴리즈 → PR 생성 후 **게시하지 않고 멈춘다**. 병합은 반드시 **"Create a merge commit"**(squash/rebase 는 태그 커밋을 이력에서 뺀다), 그 뒤 `gh release edit vX.Y.Z --draft=false`.

**버전 규칙**

- 개발 중 pom 은 `X.Y.Z-SNAPSHOT`, 릴리즈 태그는 `vX.Y.Z`(숫자 셋). 버전 비교는 숫자 2~4개 + 선택 한정자, 같은 번호면 정식 > SNAPSHOT.
- 날짜 모양(`2026-11-16`)·숫자 하나(`v7`) 태그는 버전이 아니다.
- 게시한 릴리즈의 태그를 옮기거나 파일을 바꾸지 않는다 — 고친 새 버전을 낸다.
- 서명은 하지 않는다(체크섬은 손상·전송 오류는 막지만 계정 탈취는 못 막는다 — 그래서 적용은 관리자가 버전을 보고 누를 때만).

---

## 11. 설치 · 업데이트 (런처)

실행 중인 JVM 은 자기 jar 를 바꿀 수 없고(Windows 는 잠김) 새 버전이 못 뜨면 되돌릴 주체가 없다 → **앱은 받고 검증만, 교체·재기동·롤백은 런처**.

**런처**: JDK 만 쓰는 약 30KB jar(Spring·로깅 라이브러리 import 금지 — 테스트로 확인). `java -jar <app>-launcher.jar [--home DIR] [--jar FILE] [--help] [--version]`.

**설치 폴더 구조**

```
<app>-launcher.jar
.env                      ← 런처·앱 공통 설정 (.env 가 환경변수보다 우선, 앱에도 전달)
lib/  data/ (db-backups/, updates/)
versions/<v>/<app>-<v>.jar  ← 버전마다 폴더, 덮어쓰지 않음
current.txt               ← 현재 버전 포인터 (원자적 교체 = 업데이트·롤백)
launcher.lock             ← 같은 폴더 런처 중복 실행 방지
```

- 첫 실행: 설치 폴더(없으면 `target/`)의 최신 앱 jar 를 `versions/` 에 설치.
- 서비스 등록: 서비스 관리자(systemd·작업 스케줄러·NSSM)에 위 한 줄을 등록.

**업데이트 순서 (앱 쪽)** — 관리자가 화면에서 버전을 확인하고 누를 때만:

1. 받기: `.sha256` 과 jar 를 `UPDATE_DIR` 에. 크기 정확 일치·빈 공간·주소 화이트리스트(`https://github.com/{repo}/releases/download/`).
2. 검증: SHA-256 = 체크섬 파일 · 매니페스트 `Start-Class` · jar 의 `build.version` = 태그 · `Min-Launcher-Protocol` ≤ 런처 세대.
3. 백업: `pre-update-<버전>` DB 백업 — **실패하면 멈춤**(마이그레이션은 앞으로만 간다).
4. `pending.properties`(줄 단위 `key=value`) 작성 → **종료 코드 42**.

**업데이트 순서 (런처 쪽)**: SHA-256 재확인 → 경로·버전 이름 안전성(`../` 거절) → `versions/<v>/` 설치 → `trial.properties` → **포인터 교체(마지막)** → `pending` 삭제 → 헬스(`/api/v1/health` 200)를 `RAG_HEALTH_TIMEOUT`(180초) 안에 못 내면 **자동 롤백**(포인터 원복 + 업데이트 전 DB 백업 복원, `-wal`/`-shm` 삭제 — 새 버전이 한 번도 정상 응답하지 않았을 때만) → `result.properties`(`ok`/`rolled-back`/`rejected` + `reason`). 성공 뒤 `RAG_KEEP_VERSIONS`(3)개만 남긴다.

**그 밖의 런처 책임**

- 비정상 종료 재시작(`RAG_CRASH_LIMIT`/`RAG_CRASH_WINDOW`), 종료 코드 42 는 업데이트용 재시작.
- 런처가 강제 종료돼도 앱이 고아가 되지 않게: 런처 PID·시작 시각을 환경변수로 넘기고 앱의 워치독이 주기적으로 확인해 스스로 종료.
- 중간에 죽었다 다시 떠도 `pending`/`trial` 파일로 이어 간다.
- **호환 세대**: 런처는 자기 교체를 못 하므로 약속(pending 형식·종료 코드·환경변수)을 거의 바꾸지 않는다. 호환되지 않게 바꿀 때만 `Launcher.PROTOCOL` 과 pom `min.launcher.protocol` 을 함께 올리고, 낮은 런처는 받기 직후·설치 직전에 `launcher-too-old` 로 멈춘다.
- 런처가 앱에 넘기는 값(`*_SUPERVISED=1`, `UPDATE_DIR`, PID, 프로토콜)은 사용자가 설정하지 않는다 — 런처 값이 이긴다. 런처 밖 실행(IDE·mvn·Docker)이면 업데이트 버튼은 꺼지고 알림만.

**새 버전 알림**: 기본 꺼짐(`UPDATE_CHECK_ENABLED`). 켜면 기동 직후 + cron(기본 매일 09:00). 공개 저장소·토큰 없음, 수동 확인은 10초 간격(비인증 한도 60회/시). 외부 응답은 신뢰 경계 밖 — 본문 크기 상한, https 링크만, 노트는 `textContent` 로만 렌더.

---

## 12. Docker 배포

- 멀티 스테이지: `eclipse-temurin:21-jdk-alpine` 빌더(`mvn dependency:go-offline` 을 소스 복사 전에 — 레이어 캐시) → `21-jre-alpine` 런타임.
- **비-root 실행, UID 고정**(예: 10001) — bind mount 소유권이 업그레이드마다 깨지지 않게. ENTRYPOINT 에서 데이터 폴더 쓰기 권한을 먼저 검사하고, 안 되면 `chown` 명령을 알려 주며 종료.
- 힙: `JAVA_OPTS="-XX:MaxRAMPercentage=70"`(힙 밖 네이티브 메모리 몫을 남긴다), 운영자가 덮어쓸 수 있게.
- `ENTRYPOINT ["sh","-c","… exec java $JAVA_OPTS -jar app.jar"]` — `exec` 로 java 가 PID 1 이 되어 SIGTERM 이 JVM 에 닿는다.
- Compose: 외부 이미지는 **태그 고정**(`:latest` 금지), 헬스체크 경로를 실제 API 버전에 맞춘다, 선택 서비스는 `profiles`, 앱 포트는 `127.0.0.1:` 에만 바인딩하고 외부 노출은 리버스 프록시(Caddy)가, `env_file`(`required: false`)로 `.env` 를 통째로 넘기고 **컨테이너에서만 달라야 하는 값**만 `environment:` 에.
- Alpine(musl)에는 glibc 네이티브 바이너리가 안 붙는다 — 번들 대신 마운트 경로를 요구하고, 앱이 이유를 말하며 멈춘다.
- Docker 는 자동 업데이트를 지원하지 않는다(소켓 = 호스트 root). 알림만, 갱신은 `docker compose pull && docker compose up -d`.

---

## 13. Git · 커밋

- 기본 브랜치 `main`, 작업은 브랜치(`fix/…`, `feat/…`), 릴리즈는 `release/X.Y.Z`.
- 커밋 메시지: Conventional Commits 스타일 `type(scope): 요약` — `feat`·`fix`·`docs(파일명)`·`test`·`chore`·`release`. 예: `docs(PITFALLS): drop stale facts and condense narratives`, `test: leave long-running tests out of the everyday build`. 릴리즈 커밋은 스크립트가 만드는 `release: vX.Y.Z` / `chore: begin X.Y.Z-SNAPSHOT`.
- 커밋에 `.env`·자격 증명 파일 금지(훅). 생성된 산출물·`data/`·`target/` 은 `.gitignore`.
- 스키마·메타데이터 키·기본값을 바꾸는 커밋은 관련 문서(DATABASE.md·매뉴얼·`.env.example`)를 같은 커밋에 포함.

---

## 14. 새 프로젝트 시작 체크리스트

- [ ] `pom.xml`: Java 21, 부모 Boot, `build-info`, surefire `groups`/`excludedGroups` 속성 + `all-tests`/`long-running-tests` 프로파일, (필요하면) 런처 jar 실행·`Min-Launcher-Protocol`, JaCoCo 게이트
- [ ] `mvnw`/`mvnw.cmd`/`.mvn/wrapper` 포함
- [ ] `.gitattributes`(cmd/bat/ps1 CRLF, sh LF) · `.gitignore`(`.env`, `data/`, `target/`)
- [ ] `scripts/hooks/pre-commit` + `scripts/install-hooks.sh`
- [ ] `.env.example` — 모든 환경변수, 값은 비움, 기본값은 `application.properties` 에만
- [ ] `src/test/resources/junit-platform.properties`(클래스 병렬) + `@ResourceLock` 컨벤션 테스트
- [ ] 컨벤션 테스트 기본 세트: 빈 생성자 `@Autowired` · 시간대 · 메시지 번들 · null-safe 접근자
- [ ] `messages.properties` / `messages_ko.properties`
- [ ] Flyway `db/migration`, DB 시각 UTC 헬퍼, `app.time-zone`
- [ ] `TraceIdFilter` + MDC 전파 래퍼, 감사 로그 appender
- [ ] `scripts/run_by_mvn.sh|.cmd`, `scripts/run_by_jar.sh|.cmd`(§8·§9 규칙)
- [ ] `scripts/release.sh` + `scripts/release_windows.cmd` + 하네스 테스트(`@Tag("long-running")`)
- [ ] `Dockerfile`(비-root·UID 고정·`exec`) · `docker-compose.yml`(태그 고정·`env_file`)
- [ ] `README.md`/`README.kr.md`, `CLAUDE.md`, `documents/PITFALLS.md`·`OPERATOR_MANUAL.md`·`DATABASE.md`
- [ ] `LICENSE`·`NOTICE` 와 화면의 라이선스 표시가 같은지 확인하는 테스트
- [ ] `.claude/launch.json` 실행 구성
