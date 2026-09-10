package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.dto.ProfileForm;
import com.travel.travel_recommendation.service.ProfileService;
import com.travel.travel_recommendation.service.ProfileUpdateException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@Profile({"sqlserver", "sqlserver-windows"})
@RequiredArgsConstructor
public class ProfileController {
    private final ProfileService profiles;

    @InitBinder("profileForm")
    void bindProfile(WebDataBinder binder) {
        binder.setAllowedFields("nickname", "currentPassword", "newPassword", "newPasswordConfirm");
    }

    @GetMapping("/account")
    public String profile(Authentication authentication, Model model, HttpServletResponse response) {
        model.addAttribute("loginId", authentication.getName());
        ProfileForm form = new ProfileForm();
        try {
            form.setNickname(profiles.getNickname(authentication.getName()));
        } catch (DataAccessException ex) {
            response.setStatus(503);
            return "account-unavailable";
        }
        model.addAttribute("profileForm", form);
        return "account";
    }

    @PostMapping("/account")
    public String update(@Valid @ModelAttribute ProfileForm profileForm, BindingResult result,
                         Authentication authentication, Model model, RedirectAttributes redirect,
                         HttpServletRequest request, HttpServletResponse response) {
        model.addAttribute("loginId", authentication.getName());
        if (!result.hasErrors()) {
            try {
                boolean passwordChanged = profiles.update(authentication.getName(), profileForm);
                if (passwordChanged) {
                    // 저장이 성공한 뒤 현재 세션을 종료한다. 새 비밀번호로 다시 로그인한다.
                    new SecurityContextLogoutHandler().logout(request, response, authentication);
                    return "redirect:/login?passwordChanged";
                }
                redirect.addFlashAttribute("profileUpdated", true);
                return "redirect:/account";
            } catch (ProfileUpdateException ex) {
                if (ex.getField() == null) result.reject("profile.unchanged", ex.getMessage());
                else result.rejectValue(ex.getField(), "profile.invalid", ex.getMessage());
            } catch (DataIntegrityViolationException ex) {
                result.reject("profile.conflict", "닉네임이 사용 중이거나 저장할 수 없는 값입니다. 확인 후 다시 시도해 주세요.");
            } catch (DataAccessException ex) {
                result.reject("profile.unavailable", "일시적으로 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            }
        }
        profileForm.setCurrentPassword(null);
        profileForm.setNewPassword("");
        profileForm.setNewPasswordConfirm("");
        return "account";
    }
}
