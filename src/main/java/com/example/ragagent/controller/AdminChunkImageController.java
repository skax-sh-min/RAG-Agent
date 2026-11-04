package com.example.ragagent.controller;

import com.example.ragagent.service.ChunkImageService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@code /admin} 청크 편집의 그림 추가·삭제·교체. 전부 {@code /admin/**} 아래라 관리자만 부른다(보안 설정이 게이트).
 * 저장하지 않는다 — 본문 텍스트를 받아 바뀐 텍스트를 돌려줄 뿐이고, 저장은 청크 「저장」 하나다({@link ChunkImageService} 참고).
 */
@Controller
public class AdminChunkImageController {

    private final ChunkImageService chunkImageService;

    public AdminChunkImageController(ChunkImageService chunkImageService) {
        this.chunkImageService = chunkImageService;
    }

    /** 그림 파일 업로드 → {@code {path, url}}. 본문에 넣는 것은 이어서 {@code edit(insert|replace)} 가 한다. */
    @PostMapping(value = "/admin/chunk-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, String>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String path = chunkImageService.upload(file);
        return ResponseEntity.ok(Map.of("path", path, "url", ChunkImageService.urlOf(path)));
    }

    /** 본문 속 마커 목록 — 편집 패널의 「이미지」 목록을 그린다. */
    @PostMapping("/admin/chunk-images/markers")
    @ResponseBody
    public ResponseEntity<List<ChunkImageService.MarkerView>> markers(@RequestBody Map<String, Object> body) {
        String text = body.get("text") instanceof String s ? s : "";
        return ResponseEntity.ok(chunkImageService.markers(text));
    }

    /** 그림 추가(insert) · 교체(replace) · 삭제(remove) · 설명 생성(describe) — 바뀐 본문과 마커 목록을 돌려준다. */
    @PostMapping("/admin/chunk-images/edit")
    @ResponseBody
    public ResponseEntity<ChunkImageService.EditResult> edit(@RequestBody ChunkImageService.EditRequest request) {
        return ResponseEntity.ok(chunkImageService.edit(request));
    }
}
