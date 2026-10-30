package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.config.SecurityConfig;
import com.travel.travel_recommendation.dto.ProfileForm;
import com.travel.travel_recommendation.service.KakaoMapClient;
import com.travel.travel_recommendation.service.ProfileService;
import com.travel.travel_recommendation.service.ProfileUpdateException;
import com.travel.travel_recommendation.service.RecommendationException;
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
import org.springframework.transaction.TransactionException;
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
    private final KakaoMapClient maps;
    private final org.springframework.beans.factory.ObjectProvider<com.travel.travel_recommendation.service.WithdrawalService> withdrawals;

    @InitBinder("profileForm")
    void bindProfile(WebDataBinder binder) {
        binder.setAllowedFields("nickname", "homeArea", "currentPassword", "newPassword", "newPasswordConfirm");
    }

    @GetMapping("/account")
    public String profile(Authentication authentication, Model model, HttpServletResponse response) {
        model.addAttribute("loginId", authentication.getName());
        ProfileForm form = new ProfileForm();
        try {
            form.setNickname(profiles.getNickname(authentication.getName()));
            form.setHomeArea(profiles.getHomeArea(authentication.getName()));
            addWithdrawalInfo(authentication, model);
        } catch (DataAccessException | TransactionException ex) {
            response.setStatus(503);
            return "account-unavailable";
        }
        model.addAttribute("profileForm", form);
        return "account";
    }

    // 탈퇴 안내에 쓰는 정보. 관리자 계정은 탈퇴할 수 없으므로 안내만 보이고,
    // 회원에게는 탈퇴하면 취소되는 진행 중 예약 건수를 보여 준다.
    private void addWithdrawalInfo(Authentication authentication, Model model) {
        boolean admin = SecurityConfig.startPage(authentication).equals("/admin");
        model.addAttribute("adminAccount", admin);
        var service = withdrawals.getIfAvailable();
        if (!admin && service != null) {
            model.addAttribute("activeReservations", service.countActiveReservations(authentication.getName()));
        }
    }

    /** 회원 본인 탈퇴(SFR-01-05). 현재 비밀번호와 동의를 확인한 뒤 처리하고 바로 로그아웃한다. */
    @PostMapping("/account/withdraw")
    public String withdraw(@RequestParam(required = false) String currentPassword,
                           @RequestParam(defaultValue = "false") boolean confirm,
                           Authentication authentication, RedirectAttributes redirect,
                           HttpServletRequest request, HttpServletResponse response) {
        var service = withdrawals.getIfAvailable();
        if (service == null) {
            redirect.addFlashAttribute("withdrawError", "지금은 탈퇴를 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
            return "redirect:/account#withdraw-title";
        }
        try {
            service.withdraw(authentication.getName(), currentPassword, confirm);
        } catch (ProfileUpdateException ex) {
            redirect.addFlashAttribute("withdrawError", ex.getMessage());
            return "redirect:/account#withdraw-title";
        } catch (DataAccessException | TransactionException ex) {
            redirect.addFlashAttribute("withdrawError", "일시적으로 탈퇴를 처리하지 못했습니다. 계정과 기록은 그대로입니다. 잠시 후 다시 시도해 주세요.");
            return "redirect:/account#withdraw-title";
        }
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return "redirect:/?withdrawn";
    }

    @PostMapping("/account")
    public String update(@Valid @ModelAttribute ProfileForm profileForm, BindingResult result,
                         Authentication authentication, Model model, RedirectAttributes redirect,
                         HttpServletRequest request, HttpServletResponse response) {
        model.addAttribute("loginId", authentication.getName());
        if (!result.hasErrors()) checkHomeArea(profileForm, authentication.getName(), result);
        if (!result.hasErrors()) {
            try {
                var saved = profiles.update(authentication.getName(), profileForm);
                if (saved.passwordChanged()) {
                    // 저장이 성공한 뒤 현재 세션을 종료한다. 새 비밀번호로 다시 로그인한다.
                    new SecurityContextLogoutHandler().logout(request, response, authentication);
                    return "redirect:/login?passwordChanged";
                }
                // 무엇이 바뀌었는지 알려 준다. 예) 닉네임, 주로 출발하는 곳(지움)을 바꿨어요.
                redirect.addFlashAttribute("profileUpdated", saved.message());
                return "redirect:/account";
            } catch (ProfileUpdateException ex) {
                if (ex.getField() == null) result.reject("profile.unchanged", ex.getMessage());
                else result.rejectValue(ex.getField(), "profile.invalid", ex.getMessage());
            } catch (DataIntegrityViolationException ex) {
                result.reject("profile.conflict", "닉네임이 사용 중이거나 저장할 수 없는 값입니다. 확인 후 다시 시도해 주세요.");
            } catch (DataAccessException | TransactionException ex) {
                result.reject("profile.unavailable", "일시적으로 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            }
        }
        profileForm.setCurrentPassword(null);
        profileForm.setNewPassword("");
        profileForm.setNewPasswordConfirm("");
        try {
            addWithdrawalInfo(authentication, model);
        } catch (DataAccessException | TransactionException ignored) {
            // 저장 오류 안내가 먼저이므로 예약 건수 없이 화면을 보여 준다.
        }
        return "account";
    }

    // 주로 출발하는 곳을 새로 적었으면 회원가입과 같이 지도에서 찾을 수 있는지 확인하고, 못 찾으면 다시 적게 한다.
    // 이미 저장된 값 그대로면 다시 찾지 않는다. 비밀번호만 바꿀 때 지도 문제로 막히지 않게 하기 위해서다.
    private void checkHomeArea(ProfileForm form, String loginId, BindingResult result) {
        String homeArea = form.getHomeArea();
        if (homeArea.isEmpty()) return;
        try {
            if (homeArea.equals(profiles.getHomeArea(loginId))) return;
        } catch (DataAccessException | TransactionException ignored) {
            // 저장 단계에서 같은 DB 오류를 안내하므로 여기서는 지도 확인만 이어 간다.
        }
        try {
            maps.findOrigin(homeArea);
        } catch (RecommendationException ex) {
            String message = "MAP_ORIGIN".equals(ex.getCode()) ? ex.getMessage()
                    : "지금은 출발지를 확인하지 못했어요. 잠시 후 다시 시도하거나 비우고 저장해 주세요.";
            result.rejectValue("homeArea", "homeArea.unknown", message);
        }
    }
}
