package com.travel.travel_recommendation.config;

import com.travel.travel_recommendation.repository.UserRepository;
import com.travel.travel_recommendation.service.LoginAttemptLimiter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.SessionFlashMapManager;

@Configuration
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserDetailsService userDetailsService,
                                            PasswordEncoder passwordEncoder, ObjectProvider<UserRepository> users,
                                            LoginAttemptLimiter loginLimiter) throws Exception {
        var accountCheck = new AccountSessionFilter(users);
        // 회원가입에도 CSRF 보호를 유지한다. 새 기능은 구현할 때 접근 규칙을 추가한다.
        return http.addFilterBefore(new LoginInputFilter(loginLimiter), org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                // 세션에서 로그인 정보를 읽은 뒤, CSRF 검사 전에 계정 상태와 비밀번호 변경 여부를 확인한다.
                .addFilterBefore(accountCheck, CsrfFilter.class)
                .authenticationManager(loginManager(userDetailsService, passwordEncoder))
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/", "/login", "/css/**", "/js/**", "/images/**", "/favicon.ico", "/error",
                            "/travel/conditions", "/travel/conditions/check", "/travel/conditions/analyze",
                            "/travel/conditions/review", "/travel/conditions/confirm", "/travel/conditions/confirmed",
                            "/travel/conditions/recommend", "/travel/conditions/edit",
                            "/travel/conditions/discover", "/travel/conditions/discover/**", "/travel/conditions/festival",
                            "/travel/recommendations", "/travel/recommendations/**").permitAll()
                    // 로그인한 회원이 열면 403 화면 대신 SignupController가 시작 화면으로 보낸다.
                    .requestMatchers("/signup", "/signup/complete").permitAll()
                    .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                    .requestMatchers("/account", "/account/withdraw").authenticated()
                    .requestMatchers("/my", "/my/**").authenticated()
                    .anyRequest().denyAll())
                .formLogin(form -> form.loginPage("/login").usernameParameter("loginId")
                    .successHandler((request, response, authentication) -> {
                        // 지금 비밀번호의 표식을 세션에 남긴다. 다른 기기에서 비밀번호를 바꾸면 이 세션을 끝내는 데 쓴다.
                        accountCheck.rememberCredential(request, authentication.getName());
                        loginLimiter.loginSucceeded(request.getParameter("loginId"), request.getRemoteAddr());
                        // 일정 초안이나 회원 화면에서 로그인하러 온 경우 그 화면으로 돌려보낸다.
                        var session = request.getSession(false);
                        Object back = session == null ? null : session.getAttribute(LOGIN_RETURN);
                        if (session != null) session.removeAttribute(LOGIN_RETURN);
                        boolean admin = startPage(authentication).equals("/admin");
                        String target = !admin && back instanceof String path && isReturnPath(path) ? path : startPage(authentication);
                        response.sendRedirect(request.getContextPath() + target);
                    })
                    .failureHandler((request, response, exception) -> {
                        // 화면에는 고정 코드만 전달한다. 비밀번호가 맞은 이용 제한 계정만 따로 알리고,
                        // 그 밖의 실패는 아이디가 있는지 드러나지 않게 같은 문구를 쓴다.
                        String reason = exception instanceof InternalAuthenticationServiceException ? "unavailable"
                                : exception instanceof LockedException ? "restricted" : "error";
                        if ("unavailable".equals(reason)) loginLimiter.loginNotChecked(request.getParameter("loginId"), request.getRemoteAddr());
                        logDenied(request, "LOGIN_" + exception.getClass().getSimpleName(), request.getParameter("loginId"));
                        keepLoginId(request, response);
                        response.sendRedirect(request.getContextPath() + "/login?" + reason);
                    }))
                .exceptionHandling(exceptions -> exceptions
                    .authenticationEntryPoint(SecurityConfig::askLogin)
                    .accessDeniedHandler(SecurityConfig::denyAccess))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/?logout")
                    .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID"))
                .build();
    }

    /**
     * 로그인 확인. 비밀번호가 맞은 뒤에만 계정 상태를 확인해, 비밀번호를 모르는 사람에게는 이용 제한 여부를 알리지 않는다(SFR-01-02 A2).
     * 탈퇴 계정은 비밀번호를 알 수 없는 값으로 바꿔 두므로 비밀번호 단계에서 공통 실패가 된다.
     * 이 확인만 쓰도록 로그인 확인기를 직접 만든다. 기본 확인기는 비밀번호보다 상태를 먼저 본다.
     */
    private static ProviderManager loginManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setPreAuthenticationChecks(user -> { });
        provider.setPostAuthenticationChecks(user -> {
            if (!user.isEnabled()) throw new DisabledException("Account unavailable");
            if (!user.isAccountNonLocked()) throw new LockedException("Account restricted");
        });
        return new ProviderManager(provider);
    }

    /** 로그인하지 않고 회원 화면을 열면 이유를 알리는 로그인 화면으로 보낸다. 보던 화면 주소는 로그인 뒤 돌아가도록 세션에 둔다. */
    private static void askLogin(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        String path = pathOf(request) + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        String accept = request.getHeader("Accept");
        // 주소창·링크로 연 화면(GET, HTML)만 기억한다. 화면 안에서 부르는 데이터 주소로 돌아가지 않게 하기 위해서다.
        if ("GET".equals(request.getMethod()) && accept != null && accept.contains("text/html") && isReturnPath(path)) {
            request.getSession().setAttribute(LOGIN_RETURN, path);
        }
        response.sendRedirect(request.getContextPath() + "/login?required");
    }

    /**
     * 권한이 없거나 CSRF 토큰이 맞지 않는 요청. 세션이 끝나 토큰이 사라진 로그인·로그아웃은
     * 403 화면 대신 알맞은 화면으로 보낸다(SFR-01-03 UC-03 A1). 그 밖에는 기존 403 화면을 보여 준다.
     */
    private static void denyAccess(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException, ServletException {
        String path = pathOf(request);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean loggedIn = authentication != null && !(authentication instanceof AnonymousAuthenticationToken);
        String loginId = loggedIn ? authentication.getName() : "/login".equals(path) ? request.getParameter("loginId") : null;
        boolean csrf = denied instanceof CsrfException;
        logDenied(request, csrf ? "CSRF" : "FORBIDDEN", loginId);
        if (csrf && "POST".equals(request.getMethod())) {
            if ("/login".equals(path)) {
                keepLoginId(request, response);
                response.sendRedirect(request.getContextPath() + "/login?expired");
                return;
            }
            // 로그인 세션이 이미 끝났다면 로그아웃된 것과 같으므로 시작 화면으로 보낸다.
            if ("/logout".equals(path) && !loggedIn) {
                response.sendRedirect(request.getContextPath() + "/?logout");
                return;
            }
        }
        new AccessDeniedHandlerImpl().handle(request, response, denied);
    }

    /** 접근 거부·로그인 실패 기록(SER-01·02·04·05). 시각은 로그 줄 앞에 찍힌다. 어디서 온 시도인지 보려고 IP를 함께 남기며, 비밀번호와 요청 본문은 남기지 않는다. */
    static void logDenied(HttpServletRequest request, String reason, String loginId) {
        log.warn("보안 기록 path={} reason={} loginId={} ip={}", request.getRequestURI(), reason, maskLoginId(loginId), request.getRemoteAddr());
    }

    /** 아이디는 앞 2글자만 남긴다(예: tr***). 영문·숫자·밑줄이 아닌 글자는 ?로 바꿔 로그 줄이 깨지지 않게 한다. */
    static String maskLoginId(String loginId) {
        if (loginId == null || loginId.isBlank()) return "-";
        String head = loginId.strip();
        head = head.substring(0, Math.min(2, head.length()));
        return head.replaceAll("[^A-Za-z0-9_]", "?") + "***";
    }

    /** 로그인에 실패하면 아이디만 다음 로그인 화면에 한 번 넘겨 다시 채운다. 비밀번호는 넘기지 않는다. */
    static void keepLoginId(HttpServletRequest request, HttpServletResponse response) {
        String loginId = request.getParameter("loginId");
        if (loginId == null || loginId.isBlank()) return;
        String kept = loginId.strip();
        // redirect 때 쓰는 Spring의 flash 저장소를 그대로 써서, 다음 로그인 화면에서만 한 번 꺼내 쓴다.
        FlashMap flash = new FlashMap();
        flash.put("failedLoginId", kept.length() > 50 ? kept.substring(0, 50) : kept);
        flash.setTargetRequestPath(request.getContextPath() + "/login");
        new SessionFlashMapManager().saveOutputFlashMap(flash, request, response);
    }

    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    public static final String LOGIN_RETURN = "loginReturnPath";
    /**
     * 로그인 후 돌아갈 수 있는 주소. 외부 주소로 보내지 않도록 정해 둔 모양만 허용한다.
     * 일정 초안, 내 정보(/account), 마이페이지(/my/...)이며, 마이페이지는 주소 뒤 조회 조건(?...)도 함께 허용한다.
     */
    public static boolean isReturnPath(String path) {
        return path != null && path.length() <= 500
                && (path.matches("/travel/recommendations/itineraries/[0-9a-fA-F-]{36}")
                    || path.equals("/account")
                    || path.matches("/my(/[A-Za-z0-9_-]+)+(\\?[A-Za-z0-9_.~%=&+/:,-]*)?"));
    }

    public static String startPage(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))
                ? "/admin" : "/";
    }

    /** 테스트에서만 끈다(src/test/resources/config/application.properties). 실제 실행에서는 늘 켜 둔다. */
    @Bean
    LoginAttemptLimiter loginAttemptLimiter(@Value("${travel.login-limit.enabled:true}") boolean enabled) {
        return new LoginAttemptLimiter(enabled);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Profile("!sqlserver & !sqlserver-windows")
    UserDetailsService userDetailsService() {
        // DB 없는 실행 확인 모드에는 로그인 가능한 계정을 만들지 않는다.
        return new InMemoryUserDetailsManager();
    }
}
