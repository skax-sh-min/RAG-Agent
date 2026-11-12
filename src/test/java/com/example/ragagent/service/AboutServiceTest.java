package com.example.ragagent.service;

import com.example.ragagent.model.AboutInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AboutServiceTest {

    @SuppressWarnings("unchecked")
    private static AboutService service(BuildProperties build) {
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(build);
        return new AboutService(provider);
    }

    @Test
    @DisplayName("빌드 정보가 있으면 버전·빌드 시각을 그대로 싣는다 — 라이선스·저작권은 상수, 실행 환경은 지금 JVM 의 값")
    void carriesTheBuildInfo() {
        Properties p = new Properties();
        p.setProperty("version", "1.2.3");
        p.setProperty("time", "2026-11-12T07:30:00Z");

        AboutInfo info = service(new BuildProperties(p)).info();

        assertThat(info.version()).isEqualTo("1.2.3");
        assertThat(info.buildTime()).isEqualTo(Instant.parse("2026-11-12T07:30:00Z"));
        assertThat(info.devBuild()).isFalse();
        assertThat(info.name()).isEqualTo("RAG Agent");
        assertThat(info.licenseName()).isEqualTo("Apache License 2.0");
        assertThat(info.licenseUrl()).startsWith("https://www.apache.org/licenses/");
        assertThat(info.copyright()).contains("RAG Agent Authors");
        assertThat(info.javaVersion()).isEqualTo(System.getProperty("java.version"));
        assertThat(info.bootVersion()).isNotBlank();
    }

    @Test
    @DisplayName("빌드 정보가 없으면(IDE 실행 등) 예외 없이 개발 빌드로 — 버전·시각만 비고 나머지는 그대로")
    void withoutBuildInfoItIsADevBuild() {
        AboutInfo info = service(null).info();

        assertThat(info.devBuild()).isTrue();
        assertThat(info.version()).isNull();
        assertThat(info.buildTime()).isNull();
        assertThat(info.licenseName()).isEqualTo("Apache License 2.0");
    }

    @Test
    @DisplayName("라이선스 상수는 저장소의 LICENSE·NOTICE 와 같다 — 한쪽만 바뀌면 화면이 거짓말을 한다")
    void licenseConstantsMatchTheRepositoryFiles() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("");
        String license = java.nio.file.Files.readString(root.resolve("LICENSE"));
        String notice = java.nio.file.Files.readString(root.resolve("NOTICE"));

        assertThat(license).contains("Apache License").contains("Version 2.0");
        assertThat(notice).contains(AboutService.COPYRIGHT);
    }
}
