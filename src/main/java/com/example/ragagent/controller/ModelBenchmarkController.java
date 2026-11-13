package com.example.ragagent.controller;

import com.example.ragagent.service.ModelBenchmarkService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * {@code /settings} LLM 라우팅 카드의 「성능 측정」 팝업 — {@link ModelBenchmarkService} 의 화면.
 *
 * <p>전부 {@code /admin/**} 아래라 관리자만 연다(측정 요청은 원격 프로바이더에 과금될 수 있고 채팅과 같은 서버를 쓴다).
 * 측정은 백그라운드에서 돌고 결과 조각({@code model-benchmark :: job})을 화면이 2초마다 다시 읽는다.
 */
@Controller
public class ModelBenchmarkController {

    private final ModelBenchmarkService benchmark;

    public ModelBenchmarkController(ModelBenchmarkService benchmark) {
        this.benchmark = benchmark;
    }

    @GetMapping("/admin/settings/benchmark")
    public String page(Model model) {
        model.addAttribute("targets", benchmark.targets());
        model.addAttribute("job", benchmark.running().orElse(null));
        return "model-benchmark";
    }

    @PostMapping("/admin/settings/benchmark/run")
    public String run(@RequestParam(name = "targets", required = false) List<String> targets,
                      @RequestParam(name = "thinkingOff", defaultValue = "false") boolean thinkingOff,
                      @RequestParam(name = "thinkingOn", defaultValue = "false") boolean thinkingOn,
                      @RequestParam(name = "rounds", defaultValue = "3") int rounds,
                      Model model) {
        try {
            model.addAttribute("job", benchmark.start(targets, new ModelBenchmarkService.Options(thinkingOff, thinkingOn, rounds)));
        } catch (ModelBenchmarkService.BusyException e) {
            model.addAttribute("job", benchmark.job(e.runningJobId()).orElse(null));
            model.addAttribute("notice", "busy");
        } catch (IllegalArgumentException e) {
            model.addAttribute("notice", "no-target");
        }
        return "model-benchmark :: job";
    }

    @GetMapping("/admin/settings/benchmark/jobs/{id}")
    public String job(@PathVariable long id, Model model) {
        model.addAttribute("job", benchmark.job(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "model-benchmark :: job";
    }

    @PostMapping("/admin/settings/benchmark/jobs/{id}/cancel")
    public String cancel(@PathVariable long id, Model model) {
        benchmark.cancel(id);
        return job(id, model);
    }
}
