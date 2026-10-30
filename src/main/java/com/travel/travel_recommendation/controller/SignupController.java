package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.config.SecurityConfig;
import com.travel.travel_recommendation.dto.SignupForm;
import com.travel.travel_recommendation.service.DuplicateUserException;
import com.travel.travel_recommendation.service.KakaoMapClient;
import com.travel.travel_recommendation.service.RecommendationException;
import com.travel.travel_recommendation.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@Profile({"sqlserver", "sqlserver-windows"})
@RequiredArgsConstructor
public class SignupController {
    private final UserService userService;
    private final KakaoMapClient maps;

    @InitBinder("signupForm")
    void bindSignupFields(WebDataBinder binder) {
        binder.setAllowedFields("loginId", "password", "passwordConfirm", "nickname", "homeArea");
    }

    @GetMapping("/signup")
    public String signupForm(Authentication authentication, Model model) {
        // 이미 로그인했다면 가입 화면 대신 시작 화면으로 보낸다(로그인 화면과 같은 방식).
        if (loggedIn(authentication)) return "redirect:" + SecurityConfig.startPage(authentication);
        model.addAttribute("signupForm", new SignupForm());
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute SignupForm signupForm, BindingResult result,
                         Authentication authentication, RedirectAttributes redirectAttributes) {
        if (loggedIn(authentication)) return "redirect:" + SecurityConfig.startPage(authentication);
        if (!result.hasErrors()) checkHomeArea(signupForm, result);
        if (!result.hasErrors()) {
            try {
                userService.signup(signupForm);
                redirectAttributes.addFlashAttribute("signupComplete", true);
                return "redirect:/signup/complete";
            } catch (DuplicateUserException ex) {
                result.rejectValue(ex.getField(), "duplicate", ex.getMessage());
            } catch (DataIntegrityViolationException ex) {
                result.reject("signup.conflict", "가입 정보를 저장하지 못했습니다. 아이디나 닉네임이 사용 중인지 확인하고 다시 시도해 주세요.");
            } catch (DataAccessException | TransactionException ex) {
                result.reject("signup.unavailable", "일시적으로 가입하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            }
        }
        // 실패 시 아이디와 닉네임만 유지한다. 비밀번호는 다시 입력받는다.
        signupForm.setPassword(null);
        signupForm.setPasswordConfirm(null);
        return "signup";
    }

    // 주로 출발하는 곳을 적었으면 지도에서 실제 위치를 찾을 수 있는지 확인한다. 못 찾으면 다시 적게 한다.
    // 일정 생성 때 같은 방법(KakaoMapClient.findOrigin)으로 찾으므로 여기서 통과한 값은 일정에서도 쓸 수 있다.
    private void checkHomeArea(SignupForm form, BindingResult result) {
        String homeArea = form.getHomeArea();
        if (homeArea == null || homeArea.isBlank()) return;
        try {
            maps.findOrigin(homeArea);
        } catch (RecommendationException ex) {
            // MAP_ORIGIN은 적은 이름을 못 찾은 경우이고, 그 밖은 지도 서비스 문제라 비우고 가입할 수 있게 안내한다.
            String message = "MAP_ORIGIN".equals(ex.getCode()) ? ex.getMessage()
                    : "지금은 출발지를 확인하지 못했어요. 비우고 가입한 뒤 내 정보에서 다시 적어 주세요.";
            result.rejectValue("homeArea", "homeArea.unknown", message);
        }
    }

    @GetMapping("/signup/complete")
    public String complete(Model model) {
        return Boolean.TRUE.equals(model.getAttribute("signupComplete")) ? "signup-complete" : "redirect:/signup";
    }

    private boolean loggedIn(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated();
    }
}
