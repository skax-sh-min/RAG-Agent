package com.example.ragagent.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 변환 MD 되쓰기 — 발생 수가 맞을 때만, 아니면 건드리지 않고 사유를 남긴다. */
class MarkdownWriteBackTest {

    @TempDir Path dir;

    private Path write(String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    @DisplayName("발생 수가 청크에서 센 수 이하이면 MD 를 치환하고, 임시 파일은 남지 않는다")
    void replacesWhenTheCountsAgree() throws IOException {
        Path md = write("d1.md", "# 가이드\nPostgreSQL 에 접속한다.\n\nPostgreSQL 드라이버.\n");

        MarkdownWriteBack.Result r = MarkdownWriteBack.apply(dir, "d1", "PostgreSQL", "Oracle", 3, true);

        assertThat(r.status()).isEqualTo(MarkdownWriteBack.APPLIED);
        assertThat(r.replaced()).isEqualTo(2);
        assertThat(Files.readString(md)).isEqualTo("# 가이드\nOracle 에 접속한다.\n\nOracle 드라이버.\n");
        assertThat(dir.resolve("d1.md.tmp")).doesNotExist();
    }

    @Test
    @DisplayName("교정본이 있으면 교정본을 고친다(재인덱싱이 읽는 것과 같은 우선순위) — 원본 변환본은 그대로")
    void prefersTheCorrectedFile() throws IOException {
        Path raw = write("d1.md", "PostgreSQL raw");
        Path corrected = write("d1_corrected.md", "PostgreSQL corrected");

        assertThat(MarkdownWriteBack.apply(dir, "d1", "PostgreSQL", "Oracle", 1, true).status()).isEqualTo(MarkdownWriteBack.APPLIED);

        assertThat(Files.readString(corrected)).isEqualTo("Oracle corrected");
        assertThat(Files.readString(raw)).isEqualTo("PostgreSQL raw");
    }

    @Test
    @DisplayName("MD 가 없으면(스캔 PDF 등) MD_MISSING — 아무것도 만들지 않는다")
    void missingMd() {
        MarkdownWriteBack.Result r = MarkdownWriteBack.apply(dir, "nope", "A", "B", 1, true);
        assertThat(r.status()).isEqualTo(MarkdownWriteBack.MISSING);
        assertThat(dir).isEmptyDirectory();
    }

    @Test
    @DisplayName("MD 의 발생 수가 청크에서 센 수보다 많거나 0 이면 MD_CONFLICT — 파일은 그대로")
    void countMismatchLeavesTheFileAlone() throws IOException {
        Path md = write("d1.md", "ABC ABC ABC");
        assertThat(MarkdownWriteBack.apply(dir, "d1", "ABC", "XYZ", 2, true).status()).isEqualTo(MarkdownWriteBack.CONFLICT);
        assertThat(Files.readString(md)).isEqualTo("ABC ABC ABC");

        assertThat(MarkdownWriteBack.apply(dir, "d1", "NOPE", "XYZ", 5, true).status()).isEqualTo(MarkdownWriteBack.CONFLICT);
        assertThat(Files.readString(md)).isEqualTo("ABC ABC ABC");
    }

    @Test
    @DisplayName("일부 청크에 바꿀 말이 남아 있으면 MD 만 바꾸지 않는다(청크와 MD 가 갈라지지 않게)")
    void leftoverChunksBlockTheWriteBack() throws IOException {
        Path md = write("d1.md", "ABC");
        MarkdownWriteBack.Result r = MarkdownWriteBack.apply(dir, "d1", "ABC", "XYZ", 1, false);
        assertThat(r.status()).isEqualTo(MarkdownWriteBack.CONFLICT);
        assertThat(Files.readString(md)).isEqualTo("ABC");
    }

    @Test
    @DisplayName("발생 수는 겹치지 않게 센다")
    void occurrencesDoNotOverlap() {
        assertThat(MarkdownWriteBack.occurrences("aaaa", "aa")).isEqualTo(2);
        assertThat(MarkdownWriteBack.occurrences("abc", "")).isZero();
        assertThat(MarkdownWriteBack.occurrences(null, "a")).isZero();
    }

    @Test
    @DisplayName("변형 규칙이면 MD 도 같은 규칙으로 센다·바꾼다 — 대문자 변형이 MD 에 남아 재인덱싱이 되돌리지 않게")
    void caseVariantRuleIsAppliedToTheMdToo() throws IOException {
        Path md = write("d1.md", "Oracle 사용\nORACLE 드라이버\noracle 설정\n");
        TermReplacement rule = new TermReplacement("Oracle", "PostgreSQL", true);

        MarkdownWriteBack.Result r = MarkdownWriteBack.apply(dir, "d1", rule, 3, true);

        assertThat(r.status()).isEqualTo(MarkdownWriteBack.APPLIED);
        assertThat(r.replaced()).isEqualTo(3);
        assertThat(Files.readString(md)).isEqualTo("PostgreSQL 사용\nPOSTGRESQL 드라이버\npostgresql 설정\n");
    }

    @Test
    @DisplayName("글자 그대로 규칙이면 MD 의 변형은 건드리지 않고 발생 수에도 세지 않는다")
    void exactRuleIgnoresVariantsInTheMd() throws IOException {
        Path md = write("d1.md", "Oracle 사용\nORACLE 드라이버\n");

        MarkdownWriteBack.Result r = MarkdownWriteBack.apply(dir, "d1", TermReplacement.exact("Oracle", "PostgreSQL"), 1, true);

        assertThat(r.status()).isEqualTo(MarkdownWriteBack.APPLIED);
        assertThat(Files.readString(md)).isEqualTo("PostgreSQL 사용\nORACLE 드라이버\n");
    }
}
