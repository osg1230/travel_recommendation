package com.travel.travel_recommendation.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProfileForm {
    @NotBlank(message = "닉네임을 입력해 주세요.")
    @Size(min = 2, max = 50, message = "닉네임은 2~50자로 입력해 주세요.")
    private String nickname;

    @NotBlank(message = "현재 비밀번호를 입력해 주세요.")
    @Size(max = 64, message = "현재 비밀번호는 64자 이내로 입력해 주세요.")
    @Pattern(regexp = "[!-~]{8,64}", message = "현재 비밀번호의 형식을 확인해 주세요.")
    private String currentPassword;

    @Pattern(regexp = "^$|(?=.*[a-zA-Z])(?=.*[0-9])[!-~]{8,64}",
            message = "새 비밀번호는 영문과 숫자를 포함한 8~64자여야 합니다. 공백은 사용할 수 없습니다.")
    private String newPassword = "";

    @Size(max = 64, message = "새 비밀번호 확인은 64자 이내로 입력해 주세요.")
    private String newPasswordConfirm = "";

    public void setNickname(String nickname) {
        this.nickname = nickname == null ? null : nickname.strip();
    }

    public void setNewPassword(String password) {
        this.newPassword = password == null ? "" : password;
    }

    public void setNewPasswordConfirm(String password) {
        this.newPasswordConfirm = password == null ? "" : password;
    }

    @AssertTrue(message = "새 비밀번호 확인이 일치하지 않습니다.")
    public boolean isPasswordMatching() {
        return newPassword.equals(newPasswordConfirm);
    }
}
