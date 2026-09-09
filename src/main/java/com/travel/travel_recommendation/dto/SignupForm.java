package com.travel.travel_recommendation.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupForm {
    @NotBlank(message = "아이디를 입력해 주세요.")
    @Pattern(regexp = "[a-zA-Z0-9_]{4,50}", message = "아이디는 영문, 숫자, 밑줄(_)로 4~50자 입력해 주세요.")
    private String loginId;

    // ASCII 64자 이하로 제한해 BCrypt의 72바이트 한도를 넘지 않게 한다.
    @NotBlank(message = "비밀번호를 입력해 주세요.")
    @Pattern(regexp = "(?=.*[a-zA-Z])(?=.*[0-9])[!-~]{8,64}", message = "비밀번호는 영문과 숫자를 포함해 8~64자로 입력해 주세요. 공백은 사용할 수 없습니다.")
    private String password;

    @NotBlank(message = "비밀번호를 한 번 더 입력해 주세요.")
    @Size(max = 64, message = "비밀번호 확인은 64자 이내로 입력해 주세요.")
    private String passwordConfirm;

    @NotBlank(message = "닉네임을 입력해 주세요.")
    @Size(min = 2, max = 50, message = "닉네임은 2~50자로 입력해 주세요.")
    private String nickname;

    public void setLoginId(String loginId) {
        this.loginId = loginId == null ? null : loginId.strip();
    }

    public void setNickname(String nickname) {
        this.nickname = nickname == null ? null : nickname.strip();
    }

    @AssertTrue(message = "비밀번호 확인이 일치하지 않습니다.")
    public boolean isPasswordMatching() {
        // 누락 안내는 각 필드에서 처리하고 여기서는 일치 여부만 확인한다.
        return password == null || passwordConfirm == null || password.equals(passwordConfirm);
    }
}
