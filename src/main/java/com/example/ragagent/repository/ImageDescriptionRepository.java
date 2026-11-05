package com.example.ragagent.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class ImageDescriptionRepository {

    private final JdbcTemplate jdbc;

    public ImageDescriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, String> findAll(List<String> imagePaths) {
        if (imagePaths.isEmpty()) return Map.of();
        String placeholders = imagePaths.stream().map(p -> "?").collect(Collectors.joining(","));
        List<Map.Entry<String, String>> rows = jdbc.query(
                "SELECT image_path, description FROM image_descriptions WHERE image_path IN (" + placeholders + ")",
                (rs, n) -> Map.entry(rs.getString("image_path"), rs.getString("description")),
                imagePaths.toArray());
        Map<String, String> result = new HashMap<>();
        for (var e : rows) result.put(e.getKey(), e.getValue());
        return result;
    }

    public void save(String path, String description, String provider) {
        // image_type 컬럼은 예전 유형 분류의 흔적이라 더 쓰지 않는다(스키마는 Flyway 라 그대로 두고 NULL 로 남는다).
        jdbc.update("""
                INSERT INTO image_descriptions (image_path, description, provider)
                VALUES (?, ?, ?)
                ON CONFLICT(image_path) DO UPDATE SET
                    description = excluded.description,
                    provider    = excluded.provider
                """, path, description, provider);
    }
}
