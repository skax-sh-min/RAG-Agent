package com.example.ragagent.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.time.Duration;
import java.util.Locale;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /** Serve the PWA manifest with the correct MIME type (Tomcat has no default
     *  mapping for the .webmanifest extension → would otherwise be octet-stream). */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> webmanifestMimeCustomizer() {
        return factory -> {
            MimeMappings mappings = new MimeMappings(MimeMappings.DEFAULT);
            mappings.add("webmanifest", "application/manifest+json");
            factory.setMimeMappings(mappings);
        };
    }

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver("lang");
        resolver.setDefaultLocale(Locale.KOREAN);
        resolver.setCookieMaxAge(Duration.ofDays(365));
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public void postHandle(HttpServletRequest request, HttpServletResponse response,
                                   Object handler, ModelAndView mav) {
                if (mav == null) return;
                // Never on a redirect: RedirectView appends simple model attributes to the target
                // URL as query parameters, so this would turn every "redirect:/x" into
                // "/x?requestURI=%2Fold%2Fpath". Only the rendered layout (base.html's nav
                // highlighting) ever reads it, and a redirect renders nothing.
                String viewName = mav.getViewName();
                if (viewName != null && viewName.startsWith("redirect:")) return;
                mav.addObject("requestURI", request.getRequestURI());
            }
        });
    }

    /*
     * CORS 매핑은 <b>일부러 없다</b> — 예전에는 {@code /api/**} 이 모든 origin 에 열려 있었다.
     *
     * <p>이 앱의 API 소비자는 전부 같은 오리진이다(채팅·문서·설정 화면의 fetch). 반면 인증 없는 두
     * 모드에서 {@code /api/v1/**} 은 permitAll + CSRF 예외라, 크로스 오리진 허용은 곧 "방문자가 연
     * 아무 페이지나 그 브라우저를 통해 코퍼스를 읽는다"가 된다 — {@code POST /api/v1/chat} 으로
     * 검색 답변을, {@code GET /api/v1/documents}·{@code /api/v1/chunks/{id}} 로 문서와 청크 본문을
     * 읽고 응답까지 그대로 가져갈 수 있었다. 폐쇄망이라도 내부 위키·CI 화면 하나의 XSS 면 충분하다.
     *
     * <p>스크립트 자동화(curl 등)는 브라우저가 아니므로 CORS 와 무관하다 — 이 매핑을 지워도 영향이
     * 없다. 다시 필요해지면 {@code allowedOrigins("*")} 가 아니라 <b>구체적인 오리진 목록</b>으로
     * 되살릴 것.
     */
}
