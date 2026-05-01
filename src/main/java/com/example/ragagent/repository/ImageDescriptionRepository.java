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

    public void save(String path, String description, String imageType, String provider) {
        jdbc.update("""
                INSERT INTO image_descriptions (image_path, description, image_type, provider)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(image_path) DO UPDATE SET
                    description = excluded.description,
                    image_type  = excluded.image_type,
                    provider    = excluded.provider
                """, path, description, imageType, provider);
    }
}
