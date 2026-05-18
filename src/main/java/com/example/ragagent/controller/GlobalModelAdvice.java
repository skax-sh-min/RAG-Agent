package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class GlobalModelAdvice {

    private final AppProperties props;

    public GlobalModelAdvice(AppProperties props) {
        this.props = props;
    }

    @ModelAttribute("authEnabled")
    public boolean authEnabled() {
        var cfg = (props != null) ? props.authSafe() : null;
        return cfg == null || cfg.enabled(); // null → default true (auth enabled)
    }
}
