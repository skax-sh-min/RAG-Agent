package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /settings} 의 편집 가능한 항목마다 라벨에 마우스를 올리면 뜨는 설명({@code settings.desc.X})이 <b>두 번들 모두에</b> 있는가.
 * 빠지면 오류가 아니라 화면에 {@code ??settings.desc.X_ko??} 같은 키가 그대로 보인다 — 실제 번들 파일로 확인한다.
 */
class SettingsDescriptionTest {

    private static Properties bundle(String name) throws IOException {
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(
                SettingsDescriptionTest.class.getClassLoader().getResourceAsStream(name), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    @Test
    @DisplayName("편집 가능한 일반 항목 전부(검색·인덱싱·LLM·화면 표시)에 한/영 설명이 있고, 비어 있지 않다")
    void everyEditableItemHasADescriptionInBothBundles() throws IOException {
        Properties ko = bundle("messages_ko.properties");
        Properties en = bundle("messages.properties");
        List<String> missing = new ArrayList<>();
        for (String labelKey : SettingsService.hotItemLabelKeys()) {
            String descKey = SettingsService.descriptionKey(labelKey);
            assertThat(descKey).as(labelKey).isNotNull();
            if (ko.getProperty(descKey, "").isBlank()) missing.add("ko " + descKey);
            if (en.getProperty(descKey, "").isBlank()) missing.add("en " + descKey);
        }
        // 읽기 전용이지만 같은 화면의 검색 묶음에 있는 리랭크 스위치도 설명을 단다.
        if (ko.getProperty("settings.desc.rerank-enabled", "").isBlank()) missing.add("ko settings.desc.rerank-enabled");
        if (en.getProperty("settings.desc.rerank-enabled", "").isBlank()) missing.add("en settings.desc.rerank-enabled");
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("descriptionKey — settings.item.X 의 짝은 settings.desc.X, 다른 모양의 라벨 키는 설명이 없다")
    void descriptionKeyPairsWithTheLabelKey() {
        assertThat(SettingsService.descriptionKey("settings.item.top-k")).isEqualTo("settings.desc.top-k");
        assertThat(SettingsService.descriptionKey("settings.thinking.site.eval")).isNull();
    }
}
