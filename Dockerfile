FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
# Resolve dependencies before copying source — cached unless pom.xml changes
RUN apk add --no-cache maven && mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Tesseract OCR (kor+eng) — needed when app.image-description.ocr-enabled=true
RUN apk add --no-cache tesseract-ocr tesseract-ocr-data-kor
# LibreOffice (WMF→PNG) is large (~500 MB); add it in a custom image if
# docx-wmf-convert=true: RUN apk add --no-cache libreoffice
COPY --from=builder /app/target/*.jar app.jar
RUN mkdir -p /app/data/documents /app/data/images
EXPOSE 8080

# 비-root 실행. 이 프로세스는 문서를 파싱하고(PDFBox/POI), OCR 을 JNA 로 같은 프로세스에 붙이며,
# sqlite-vec 를 쓰면 네이티브 확장까지 로드한다 — 전부 신뢰 경계 밖의 파일을 입력으로 받는
# 코드다. 그중 하나에서 코드 실행이 일어났을 때 컨테이너 안에서 root 인지 아닌지가 그 다음에
# 무엇을 할 수 있는지를 가른다.
#
# **UID 를 10001 로 못 박는 이유**: `./data` 는 bind mount 라 파일 소유권이 호스트 것을 그대로
# 따른다. 이미지가 UID 를 매번 다르게 잡으면 업그레이드마다 쓰기 권한이 깨진다.
#
# ⚠ **기존 배포에서 한 번만 필요한 작업**: 예전 이미지는 root 로 돌았으므로 `./data` 안의 파일이
# root 소유다. 아래 명령을 호스트에서 한 번 실행해야 이 컨테이너가 쓸 수 있다.
#     sudo chown -R 10001:10001 ./data
# 하지 않으면 아래 ENTRYPOINT 의 검사가 그 사실을 그대로 알려주고 종료한다(권한 오류가 애플리케이션
# 스택트레이스로 나오는 것보다 낫다). Docker Desktop(Windows/macOS)의 bind mount 는 소유권을
# 강제하지 않으므로 그쪽에서는 해당 없다.
RUN addgroup -g 10001 app \
 && adduser -D -u 10001 -G app app \
 && chown -R app:app /app
USER app

# 힙 상한. JVM 기본값(MaxRAMPercentage=25)은 이 앱에 너무 좁다 — 업로드 상한이 200MB 이고
# (spring.servlet.multipart.max-file-size), PDFBox/POI 는 문서를 통째로 메모리에 올리며,
# MarkdownCorrectionService 는 변환된 마크다운 전문을 문자열로 다룬다. 문서 하나가 수백 MB 를
# 요구할 수 있는데 4GB 컨테이너에서 힙이 1GB 였다.
#
# **75가 아니라 70인 이유**: 이 프로세스는 힙 밖에서도 메모리를 쓴다. OCR(tess4j)은 별도
# 프로세스가 아니라 JNA 로 같은 프로세스에 붙는 네이티브 메모리이고, sqlite-vec 확장도
# 마찬가지다. 거기에 reactor-netty 의 direct buffer 와 가상 스레드 스택이 얹히며,
# LibreOffice 를 추가한 이미지라면 soffice 는 아예 자식 프로세스로 같은 컨테이너 한도를
# 나눠 쓴다. 이 몫을 남기지 않으면 힙에는 여유가 있는데 컨테이너가 OOM kill 되는 —
# 애플리케이션 로그에 아무것도 남지 않는 — 죽음이 난다.
#
# **무엇의 70% 인가**: 컨테이너에 메모리 한도가 걸려 있으면 그 한도, 없으면 호스트 전체다
# (JVM 의 UseContainerSupport 가 cgroup 을 읽는다). 기존 동작도 같은 기준의 25% 였으므로
# 성격이 바뀌는 것은 아니고 비율만 넓어진다. 한도를 거는 방법은 docker-compose.yml 참조.
#
# 운영자는 `-e JAVA_OPTS=...` 나 .env 로 통째로 덮어쓸 수 있다(컨테이너 실행 시 준 값이
# 이미지의 ENV 를 이긴다). 고정 크기가 필요하면 예: JAVA_OPTS="-Xms512m -Xmx3g".
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"

# exec form 은 환경변수를 확장하지 않아 JAVA_OPTS 가 그대로 문자열로 넘어간다. sh -c 로
# 확장하되 exec 로 넘겨 java 가 PID 1 을 물려받게 한다 — 그래야 docker stop 의 SIGTERM 이
# 셸이 아니라 JVM 에 닿아 graceful shutdown 이 돈다.
#
# 앞의 쓰기 권한 검사는 위 USER 전환의 짝이다 — 이것이 없으면 root 소유로 남은 `./data` 가
# "SQLite 파일을 못 만든다"는 스택트레이스로만 드러나, 원인이 권한이라는 것도 고치는 방법도
# 로그 어디에도 없다.
ENTRYPOINT ["sh", "-c", "if [ ! -w /app/data ]; then echo \"[FATAL] /app/data 에 쓸 수 없습니다. 이 컨테이너는 UID 10001(app)로 실행되는데 마운트된 데이터 디렉터리가 다른 사용자 소유입니다(예전 이미지는 root 로 돌았습니다). 호스트에서 한 번 실행하세요:  sudo chown -R 10001:10001 ./data\" >&2; exit 1; fi; exec java $JAVA_OPTS -jar app.jar"]
