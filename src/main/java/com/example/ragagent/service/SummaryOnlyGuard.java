package com.example.ragagent.service;

import com.example.ragagent.model.ResponseMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 요약 전용 모드({@link ResponseMode#summaryOnly()})의 출력이 정말 요약 하나인지 확인하는
 * <b>안전망</b> (PLAN §6.24 Step 1-c).
 *
 * <p><b>무조건 재작성에서 조건부 가드로 강등됐다.</b> 예전에는 S 답변을 매번 다시 썼다 — 요약
 * 섹션을 뽑아내고, 빈 줄을 전부 제거하고, 7줄로 자르고, {@code "## 요약"}을 다시 붙였다. 모델이
 * 지시를 완벽히 따른 답변까지 그 처리를 거치면서 문단·목록 구조가 바뀌었고, 스트리밍에서는
 * 화면에 이미 그려진 텍스트와 저장된 텍스트가 달라졌다. Step 1-a에서 모드별 전용 시스템
 * 프롬프트가 생겨 모델이 애초에 요약만 쓰게 됐으므로, 이제 후처리는 <b>그게 실패했을 때만</b>
 * 돈다.
 *
 * <p>발동은 {@code INFO}로 남긴다 — 이 로그의 빈도가 곧 <b>새 프롬프트가 실제로 먹히는지에 대한
 * 유일한 측정치</b>다. 조용히 고쳐주면 프롬프트가 나빠져도 아무도 모른다.
 *
 * <p><b>글자 수로 자르지 않는다.</b> 분량은 프롬프트의 몫이고("1,000자 이내"), 1,200자짜리 좋은
 * 요약을 문장 중간에서 끊는 쪽이 200자 초과보다 나쁘다. 이 가드가 보는 것은 <b>구조</b>뿐이다.
 */
final class SummaryOnlyGuard {

    private static final Logger log = LoggerFactory.getLogger(SummaryOnlyGuard.class);

    /** 마크다운 헤딩 줄. 들여쓰기 3칸까지는 여전히 헤딩이고 4칸부터는 코드 블록이다. */
    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}\\s");
    /** 펜스 시작/끝. 헤딩처럼 보이는 코드 주석(`# comment`)을 헤딩으로 세지 않기 위해 필요하다. */
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(```|~~~)");

    private static final String SUMMARY_HEADING = "## 요약";

    private SummaryOnlyGuard() {}

    /**
     * 요약 전용 모드가 아니거나 이미 규약을 지킨 답변은 <b>그대로 돌려준다</b>(동일 참조).
     * 섹션이 두 개 이상이면 첫 섹션만 남기고, 헤딩이 하나도 없으면 {@code "## 요약"}만 앞에 붙인다.
     */
    static String apply(String answer, ResponseMode mode) {
        if (!mode.summaryOnly() || answer == null || answer.isBlank()) return answer;

        List<String> headings = headingLines(answer);
        if (headings.size() == 1) return answer;                    // 규약 준수 — 손대지 않는다

        if (headings.isEmpty()) {
            // 내용은 그대로 두고 헤딩만 보충한다. 다운스트림(대화 요약 재사용·큐레이션)이
            // "## 요약" 섹션의 존재에 기대고 있어, 없으면 그 경로들이 조용히 폴백한다.
            return SUMMARY_HEADING + "\n" + answer.strip();
        }

        String salvaged = firstSectionOnly(answer);
        log.info("[S-GUARD] 요약 전용 후처리 발동 — 모델이 섹션 {}개를 생성했다 ({}자 → {}자). "
                 + "이 로그가 잦으면 prompt.answer.system.s 가 먹히지 않는다는 신호다.",
                headings.size(), answer.length(), salvaged.length());
        return salvaged;
    }

    /** 펜스 밖의 헤딩 줄만 모은다. */
    private static List<String> headingLines(String text) {
        boolean inFence = false;
        List<String> found = new java.util.ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            if (FENCE.matcher(line).find()) { inFence = !inFence; continue; }
            if (!inFence && HEADING.matcher(line).find()) found.add(line);
        }
        return found;
    }

    /**
     * 두 번째 헤딩 앞까지만 남긴다 — 첫 섹션의 <b>본문은 한 글자도 건드리지 않는다</b>.
     * 빈 줄을 지우거나 줄 수를 자르던 예전 동작이 준수한 답변까지 망가뜨린 원인이었다.
     */
    private static String firstSectionOnly(String text) {
        boolean inFence = false;
        int headingsSeen = 0;
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            if (FENCE.matcher(line).find()) inFence = !inFence;
            else if (!inFence && HEADING.matcher(line).find() && ++headingsSeen == 2) break;
            kept.append(line).append('\n');
        }
        String result = kept.toString().strip();
        return result.isBlank() ? SUMMARY_HEADING + "\n요약할 내용이 없습니다." : result;
    }
}
