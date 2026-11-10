package com.example.ragagent.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 본문 해시의 단일 출처(§6.33 4b) — 수정안이 만들어진 시점의 청크 본문({@code change_proposal.before_hash})과 적용 시점의 현재 본문을 같은 함수로
 * 비교한다. 두 자리가 따로 해시를 만들면 어긋나는 순간 모든 수정안이 {@code stale} 이 된다.
 */
public final class TextHash {

    private TextHash() {}

    /** SHA-256 소문자 16진수(64자). {@code null} 은 빈 문자열로 본다. */
    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
