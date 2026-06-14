package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.security.SqliteUserDetailsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;
import java.util.regex.Pattern;

@Controller
public class AuthController {

    // 10–72 chars: BCrypt silently truncates beyond 72 bytes
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[a-zA-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~]).{10,72}$"
    );
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"
    );

    private final SqliteUserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;

    public AuthController(SqliteUserDetailsService userDetailsService,
                          PasswordEncoder passwordEncoder,
                          AppProperties props) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    // ── First-run setup (no-auth mode only) ─────────────────────────────

    @GetMapping("/setup")
    public String setupPage() {
        if (props.authSafe().enabled()) return "redirect:/";
        if (userDetailsService.findFirstAdmin().isPresent()) return "redirect:/";
        return "auth/setup";
    }

    @PostMapping("/setup")
    public String setup(@RequestParam String email,
                        @RequestParam(defaultValue = "") String displayName,
                        @RequestParam String password,
                        @RequestParam String passwordConfirm,
                        RedirectAttributes redirectAttributes) {
        if (props.authSafe().enabled()) return "redirect:/";
        if (userDetailsService.findFirstAdmin().isPresent()) return "redirect:/";

        String trimmedEmail   = email.trim();
        String trimmedDisplay = displayName.trim();

        if (!EMAIL_PATTERN.matcher(trimmedEmail).matches()) {
            redirectAttributes.addFlashAttribute("error", "auth.signup.error.email.invalid");
            return "redirect:/setup";
        }
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            redirectAttributes.addFlashAttribute("error", "auth.signup.error.password.weak");
            return "redirect:/setup";
        }
        if (!password.equals(passwordConfirm)) {
            redirectAttributes.addFlashAttribute("error", "auth.signup.error.password.mismatch");
            return "redirect:/setup";
        }

        userDetailsService.createAdminUser(
                UUID.randomUUID().toString(),
                trimmedEmail,
                passwordEncoder.encode(password),
                trimmedDisplay.isEmpty() ? null : trimmedDisplay
        );
        return "redirect:/";
    }

    // ── Auth endpoints ───────────────────────────────────────────────────

    @GetMapping("/login")
    public String loginPage(@RequestParam(required = false) String error,
                            @RequestParam(required = false) String logout,
                            Model model) {
        if (error != null) model.addAttribute("error", true);
        if (logout != null) model.addAttribute("logout", true);
        return "auth/login";
    }

    @GetMapping("/signup")
    public String signupPage() {
        return "auth/signup";
    }

    @PostMapping("/signup")
    public String signup(@RequestParam String email,
                         @RequestParam(defaultValue = "") String displayName,
                         @RequestParam String password,
                         @RequestParam String passwordConfirm,
                         HttpServletRequest request,
                         RedirectAttributes redirectAttributes) {

        String trimmedEmail = email.trim();
        String trimmedDisplay = displayName.trim();

        if (!EMAIL_PATTERN.matcher(trimmedEmail).matches()) {
            redirectAttributes.addFlashAttribute("error", "auth.signup.error.email.invalid");
            return "redirect:/signup";
        }
        if (userDetailsService.emailExists(trimmedEmail)) {
            redirectAttributes.addFlashAttribute("error", "auth.signup.error.email.taken");
            return "redirect:/signup";
        }
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            redirectAttributes.addFlashAttribute("error", "auth.signup.error.password.weak");
            return "redirect:/signup";
        }
        if (!password.equals(passwordConfirm)) {
            redirectAttributes.addFlashAttribute("error", "auth.signup.error.password.mismatch");
            return "redirect:/signup";
        }

        String id = UUID.randomUUID().toString();
        String hash = passwordEncoder.encode(password);
        try {
            userDetailsService.createUser(id, trimmedEmail, hash, trimmedDisplay.isEmpty() ? null : trimmedDisplay);
        } catch (DataIntegrityViolationException e) {
            // Concurrent signup with same email slipped past emailExists() check
            redirectAttributes.addFlashAttribute("error", "auth.signup.error.email.taken");
            return "redirect:/signup";
        }

        // Auto-login after registration
        AppUserDetails userDetails = (AppUserDetails) userDetailsService.loadUserByUsername(trimmedEmail);
        UsernamePasswordAuthenticationToken auth =
                UsernamePasswordAuthenticationToken.authenticated(userDetails, null, userDetails.getAuthorities());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        HttpSession old = request.getSession(false);
        if (old != null) old.invalidate();
        HttpSession session = request.getSession(true);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, ctx);

        return "redirect:/";
    }
}
