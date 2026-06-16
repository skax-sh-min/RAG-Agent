package com.example.ragagent.model;

/** Vector Store / Document 메타데이터 키. 모든 메타 접근은 이 상수를 사용. */
public final class MetaKey {
    // 현재 사용
    public static final String DOC_ID        = "doc_id";
    public static final String FILENAME      = "filename";
    public static final String VERSION       = "version";
    public static final String PAGE_OR_SLIDE = "page_or_slide";
    public static final String IMAGE_PATHS   = "image_paths";
    public static final String SOURCE_TYPE   = "source_type";
    public static final String DOC_TYPE      = "doc_type";
    public static final String SHA256        = "sha256";
    public static final String COLLECTED_AT  = "collected_at";
    public static final String CHUNK_INDEX     = "chunk_index";       // R-4: 안정적 청크 식별자 (0-based)
    public static final String EXCERPT_KEYWORDS = "excerpt_keywords"; // R-2: 청크 키워드 (하이브리드 검색)

    // 미래 확장 — 멀티유저/권한
    public static final String OWNER_ID  = "owner_id";   // 'anonymous' 기본
    public static final String TENANT_ID = "tenant_id";  // B2B 분리
    public static final String VISIBILITY = "visibility"; // private | shared | public

    private MetaKey() {}
}
