package com.example.ragagent.controller;

import com.example.ragagent.repository.AgentToolCallRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * {@code /admin} 의 Tool 호출 추적 패널(§6.33 5단계) — Tool 별 선택 분포·실행 성공률·지연·폴백 비율. 배포 전체를 보는 운영자 뷰라 사용자별로 나누지
 * 않고 {@code /admin/**} 의 ROLE_ADMIN 게이트를 그대로 받는다. 읽기 전용이며 질문 본문은 어디에도 없다.
 */
@Controller
public class AgentToolPanelController {

    /** 화면이 고르는 기간(일) — 그 밖의 값은 가장 가까운 허용 값으로 눌린다. */
    static final int[] ALLOWED_DAYS = {1, 7, 14, 30, 90};

    private final AgentToolCallRepository calls;
    private final boolean agentEnabled;

    public AgentToolPanelController(AgentToolCallRepository calls, @Value("${app.agent.enabled:false}") boolean agentEnabled) {
        this.calls = calls;
        this.agentEnabled = agentEnabled;
    }

    @GetMapping("/admin/agent-tools")
    public String panel(@RequestParam(defaultValue = "14") int days, Model model) {
        int d = clampDays(days);
        model.addAttribute("agentEnabled", agentEnabled);
        model.addAttribute("days", d);
        model.addAttribute("allowedDays", ALLOWED_DAYS);
        model.addAttribute("summary", calls.summary(d));
        return "fragments/admin-agent-tools :: panel";
    }

    static int clampDays(int days) {
        int best = ALLOWED_DAYS[0];
        for (int a : ALLOWED_DAYS) {
            if (Math.abs(a - days) < Math.abs(best - days)) best = a;
        }
        return best;
    }
}
