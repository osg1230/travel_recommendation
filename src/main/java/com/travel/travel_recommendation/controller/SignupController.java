package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.dto.SignupForm;
import com.travel.travel_recommendation.service.DuplicateUserException;
import com.travel.travel_recommendation.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
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

    @InitBinder("signupForm")
    void bindSignupFields(WebDataBinder binder) {
        binder.setAllowedFields("loginId", "password", "passwordConfirm", "nickname");
    }

    @GetMapping("/signup")
    public String signupForm(Model model) {
        model.addAttribute("signupForm", new SignupForm());
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute SignupForm signupForm, BindingResult result,
                         RedirectAttributes redirectAttributes) {
        if (!result.hasErrors()) {
            try {
                userService.signup(signupForm);
                redirectAttributes.addFlashAttribute("signupComplete", true);
                return "redirect:/signup/complete";
            } catch (DuplicateUserException ex) {
                result.rejectValue(ex.getField(), "duplicate", ex.getMessage());
            } catch (DataIntegrityViolationException ex) {
                result.reject("signup.conflict", "가입 정보를 저장하지 못했습니다. 아이디나 닉네임이 사용 중인지 확인하고 다시 시도해 주세요.");
            } catch (DataAccessException ex) {
                result.reject("signup.unavailable", "일시적으로 가입하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            }
        }
        // 실패 시 아이디와 닉네임만 유지한다. 비밀번호는 다시 입력받는다.
        signupForm.setPassword(null);
        signupForm.setPasswordConfirm(null);
        return "signup";
    }

    @GetMapping("/signup/complete")
    public String complete(Model model) {
        return Boolean.TRUE.equals(model.getAttribute("signupComplete")) ? "signup-complete" : "redirect:/signup";
    }
}
