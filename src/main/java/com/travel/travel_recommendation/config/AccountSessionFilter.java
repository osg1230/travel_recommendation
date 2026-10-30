package com.travel.travel_recommendation.config;

import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.entity.UserStatus;
import com.travel.travel_recommendation.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 로그인한 요청마다 계정 상태와 비밀번호 변경 여부를 확인한다(SFR-15-03, SFR-01-04·05).
 * 관리자가 이용 제한했거나, 탈퇴했거나, 다른 기기에서 비밀번호를 바꿨으면 남아 있던 세션을 끝낸다.
 */
public class AccountSessionFilter extends OncePerRequestFilter {
    /** 로그인할 때 세션에 두는 '자격 증명 표식'. 비밀번호 해시 자체가 아니라 그 SHA-256 값만 둔다. */
    static final String CREDENTIAL_MARK = "credentialMark";
    private final ObjectProvider<UserRepository> users;

    AccountSessionFilter(ObjectProvider<UserRepository> users) {
        this.users = users;
    }

    /** 로그인 성공 직후 호출한다. 지금 비밀번호의 표식을 세션에 남긴다. */
    void rememberCredential(HttpServletRequest request, String loginId) {
        var repository = users.getIfAvailable();
        if (repository == null) return;
        try {
            repository.findByLoginId(loginId)
                    .ifPresent(user -> request.getSession().setAttribute(CREDENTIAL_MARK, mark(user.getPasswordHash())));
        } catch (DataAccessException | TransactionException ex) {
            // 표식을 남기지 못해도 로그인은 이어 간다. 표식이 없는 세션은 계정 상태만 확인한다.
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // CSS·JS·이미지는 계정과 상관없으므로 DB를 조회하지 않는다.
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/")
                || path.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        var repository = users.getIfAvailable();
        // DB 없이 실행하는 모드이거나 로그인하지 않은 요청은 확인할 것이 없다.
        if (repository == null || authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response);
            return;
        }
        String reason;
        try {
            reason = endReason(repository.findByLoginId(authentication.getName()).orElse(null), request.getSession(false));
        } catch (DataAccessException | TransactionException ex) {
            // DB를 잠시 쓸 수 없으면 확인을 건너뛴다. 각 화면이 DB 오류 안내를 따로 보여 준다.
            chain.doFilter(request, response);
            return;
        }
        if (reason == null) {
            chain.doFilter(request, response);
            return;
        }
        // 세션을 끝내고, 왜 로그아웃됐는지 알 수 있는 화면으로 보낸다.
        SecurityConfig.logDenied(request, reason, authentication.getName());
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        String target = switch (reason) {
            case "SESSION_WITHDRAWN" -> "/?withdrawn";
            case "SESSION_RESTRICTED" -> "/login?restricted";
            case "SESSION_PASSWORD_CHANGED" -> "/login?changed";
            default -> "/login?required";
        };
        response.sendRedirect(request.getContextPath() + target);
    }

    /** 세션을 끝내야 하면 사유 코드를, 계속 써도 되면 null을 돌려준다. */
    private String endReason(User user, HttpSession session) {
        if (user == null) return "SESSION_ACCOUNT_MISSING";
        if (user.getStatus() == UserStatus.WITHDRAWN) return "SESSION_WITHDRAWN";
        if (user.getStatus() == UserStatus.RESTRICTED) return "SESSION_RESTRICTED";
        // 표식이 없는 세션(테스트용 인증 등)은 상태만 확인한다. 로그인 화면을 거친 세션에는 항상 표식이 있다.
        Object saved = session == null ? null : session.getAttribute(CREDENTIAL_MARK);
        if (saved != null && !saved.equals(mark(user.getPasswordHash()))) return "SESSION_PASSWORD_CHANGED";
        return null;
    }

    /** 비밀번호 해시의 SHA-256 값. 비밀번호가 바뀌면 해시가 바뀌므로 이 값도 달라진다. */
    static String mark(String passwordHash) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(passwordHash.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
