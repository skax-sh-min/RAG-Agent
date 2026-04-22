package com.example.ragagent.model;

import java.util.List;

public record SyncResult(
        List<String> indexed,
        List<String> updated,
        List<String> deleted
) {}
