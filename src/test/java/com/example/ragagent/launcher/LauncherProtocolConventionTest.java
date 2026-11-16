package com.example.ragagent.launcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 앱↔런처 호환 선언의 세 자리 — pom 의 {@code min.launcher.protocol}, 앱 jar 매니페스트의 {@code Min-Launcher-Protocol},
 * 코드의 {@link Launcher#PROTOCOL} — 가 서로 맞는지 빌드로 고정한다. 하나만 고치면 런처는 설치할 수 없는 버전을 받아들이거나
 * (요구를 낮게 적음) 어떤 런처도 설치할 수 없는 릴리즈가 나간다(요구를 런처보다 높게 적음).
 */
class LauncherProtocolConventionTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    @DisplayName("pom 의 min.launcher.protocol 은 1 이상이고 이 코드의 런처 프로토콜(Launcher.PROTOCOL)을 넘지 않는다")
    void theMinimumNeverExceedsWhatTheShippedLauncherSpeaks() throws Exception {
        Matcher m = Pattern.compile("<min\\.launcher\\.protocol>\\s*(\\d+)\\s*</min\\.launcher\\.protocol>").matcher(read("pom.xml"));

        assertThat(m.find()).as("pom.xml 에 <min.launcher.protocol> 속성이 있어야 한다").isTrue();
        int min = Integer.parseInt(m.group(1));
        assertThat(min).isGreaterThanOrEqualTo(1);
        assertThat(min).as("이 릴리즈의 런처(PROTOCOL=%d)가 설치할 수 없는 버전을 만들 수는 없다", Launcher.PROTOCOL)
                .isLessThanOrEqualTo(Launcher.PROTOCOL);
    }

    @Test
    @DisplayName("앱 jar 의 매니페스트에 그 속성이 실리도록 pom 이 연결돼 있고, 앱·런처가 같은 속성 이름을 읽는다")
    void theManifestAttributeIsWiredAndReadByBothSides() throws Exception {
        String pom = read("pom.xml");
        assertThat(pom).contains("<id>default-jar</id>")
                .contains("<" + Jars.MIN_LAUNCHER_PROTOCOL + ">${min.launcher.protocol}</" + Jars.MIN_LAUNCHER_PROTOCOL + ">");
        assertThat(read("src/main/java/com/example/ragagent/service/UpdateApplyService.java"))
                .contains("getValue(\"" + Jars.MIN_LAUNCHER_PROTOCOL + "\")");
    }

    @Test
    @DisplayName("런처 jar 는 Implementation-Version 을 매니페스트에 싣는다 — --version 과 앱 화면이 그 값을 보인다")
    void theLauncherJarCarriesItsVersion() throws Exception {
        assertThat(read("pom.xml")).contains("<addDefaultImplementationEntries>true</addDefaultImplementationEntries>");
    }
}
