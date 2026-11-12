package com.example.ragagent.service;

import com.example.ragagent.model.AboutInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

/**
 * {@code /settings} 의 「정보」 카드가 읽는 값 — 버전·빌드 시각은 빌드가 만든 {@code META-INF/build-info.properties}
 * ({@link BuildProperties}, pom 의 {@code spring-boot-maven-plugin} {@code build-info} 목표)에서, 라이선스는 이 저장소의
 * {@code LICENSE}·{@code NOTICE} 와 같은 값을 코드 상수로 둔다(라이선스가 바뀌면 두 파일과 여기를 함께 고친다).
 *
 * <p>빌드 정보는 <b>없을 수 있다</b> — IDE 에서 {@code main()} 을 바로 돌리거나 {@code build-info} 를 거치지 않은 클래스 디렉터리에서는
 * 파일이 없고, 그때 {@link BuildProperties} 빈이 만들어지지 않는다. 그래서 {@link ObjectProvider} 로 받고, 없으면
 * {@link AboutInfo#devBuild()} 가 참이다 — 화면이 비는 대신 "개발 빌드"라고 말한다.
 */
@Service
public class AboutService {

    static final String APP_NAME = "RAG Agent";
    static final String LICENSE_NAME = "Apache License 2.0";
    static final String LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0";
    static final String COPYRIGHT = "Copyright 2026 The RAG Agent Authors";

    private final ObjectProvider<BuildProperties> buildProperties;

    public AboutService(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    public AboutInfo info() {
        BuildProperties build = buildProperties.getIfAvailable();
        return new AboutInfo(APP_NAME,
                build == null ? null : build.getVersion(),
                build == null ? null : build.getTime(),
                LICENSE_NAME, LICENSE_URL, COPYRIGHT,
                System.getProperty("java.version"), SpringBootVersion.getVersion());
    }
}
