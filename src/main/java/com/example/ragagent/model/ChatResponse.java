package com.example.ragagent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ChatResponse(
        String answer,
        @JsonProperty("question_type") String questionType,
        List<String> sources
) {}
