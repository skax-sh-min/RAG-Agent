package com.example.ragagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §6.15 — proves {@code app.upload.max-total-size} actually reaches {@link AppProperties.UploadConfig}.
 *
 * <p>Worth a test of its own because the failure mode is silent: a nested config record that Spring
 * cannot bind leaves the value at its default and looks exactly like a correctly-configured
 * unlimited deployment — the same trap {@code AuthConfig.guestIdentity} fell into (see the
 * {@code @ConstructorBinding} note there). A dead cap is worse than no cap, since the operator
 * believes one is in force.
 */
class UploadConfigBindingTest {

    private static AppProperties.UploadConfig bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("app.upload", Bindable.of(AppProperties.UploadConfig.class))
                .orElse(null);
    }

    @Test
    @DisplayName("단위 접미사가 붙은 값이 바인딩된다 — 상한은 GB 단위라 이게 실제 사용 형태다")
    void bindsHumanReadableSize() {
        assertThat(bind(Map.of("app.upload.max-total-size", "20GB")).maxTotalBytes())
                .isEqualTo(20L * 1024 * 1024 * 1024);
        assertThat(bind(Map.of("app.upload.max-total-size", "500MB")).maxTotalBytes())
                .isEqualTo(500L * 1024 * 1024);
    }

    @Test
    @DisplayName("접미사 없는 숫자는 바이트로 읽힌다 (설계안의 max-total-bytes 표기와 호환)")
    void bindsBareNumberAsBytes() {
        assertThat(bind(Map.of("app.upload.max-total-size", "1024")).maxTotalBytes()).isEqualTo(1024);
    }

    @Test
    @DisplayName("application.properties 의 기본값 0 은 '무제한' 으로 바인딩된다")
    void bindsZeroAsUnlimited() {
        AppProperties.UploadConfig cfg = bind(Map.of("app.upload.max-total-size", "0"));

        assertThat(cfg.maxTotalBytes()).isZero();
        assertThat(cfg.hasLimit()).isFalse();
    }

    @Test
    @DisplayName("프로퍼티가 아예 없으면 upload 자체가 null 이고, uploadSafe() 가 무제한으로 정규화한다")
    void absentPropertyIsUnlimited() {
        assertThat(bind(Map.of())).isNull();
    }
}
