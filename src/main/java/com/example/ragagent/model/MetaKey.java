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
    public static final String CHUNK_INDEX     = "chunk_index";       // 안정적 청크 식별자 (0-based)
    public static final String EXCERPT_KEYWORDS = "excerpt_keywords"; // 청크 키워드 (하이브리드 검색)
    public static final String TAGS            = "tags";              // 문서 태그 (검색 스코프, 쉼표 결합 문자열)
    public static final String HEADING_PAGE    = "heading_page";      // DOCX 헤딩 시작 페이지(명시적 page break 기준)
    public static final String HEADING         = "heading";           // 청크가 속한 섹션 제목 (DocumentLoaderService)
    public static final String CHUNK_CONTEXT   = "chunk_context";     // 임베딩/FTS용 맥락 헤더 (transient — 영속 전 제거, §10.1)
    public static final String SEARCH_TEXT     = "search_text";       // SearchTextBuilder 결과 캐시 (transient — 영속 전 제거, §10.8.5)

    // 미래 확장 — 멀티유저/권한
    public static final String OWNER_ID  = "owner_id";   // 'anonymous' 기본
    public static final String TENANT_ID = "tenant_id";  // B2B 분리
    public static final String VISIBILITY = "visibility"; // private | shared | public

    private MetaKey() {}
}
