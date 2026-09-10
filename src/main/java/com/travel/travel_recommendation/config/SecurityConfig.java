package com.travel.travel_recommendation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 회원가입에도 CSRF 보호를 유지한다. 새 기능은 구현할 때 접근 규칙을 추가한다.
        return http.addFilterBefore(new LoginInputFilter(), org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/", "/login", "/css/**", "/js/**", "/error").permitAll()
                    .requestMatchers("/signup", "/signup/complete").anonymous()
                    .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                    .anyRequest().denyAll())
                .formLogin(form -> form.loginPage("/login").usernameParameter("loginId")
                    .successHandler((request, response, authentication) ->
                        response.sendRedirect(request.getContextPath() + startPage(authentication)))
                    .failureHandler((request, response, exception) -> {
                        // 예외와 입력값을 세션에 남기지 않고 고정 코드만 화면에 전달한다.
                        String reason = exception instanceof InternalAuthenticationServiceException ? "unavailable" : "error";
                        response.sendRedirect(request.getContextPath() + "/login?" + reason);
                    }))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/?logout")
                    .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID"))
                .build();
    }

    public static String startPage(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))
                ? "/admin" : "/";
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
