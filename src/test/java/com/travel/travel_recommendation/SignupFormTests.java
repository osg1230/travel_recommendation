package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.SignupForm;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignupFormTests {
    @Test
    void validatesFormatAndLengthBoundaries() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            SignupForm form = new SignupForm();
            form.setLoginId("a".repeat(50));
            form.setNickname("가".repeat(50));
            form.setPassword("Ab1!".repeat(16));
            form.setPasswordConfirm(form.getPassword());
            // 주로 출발하는 곳은 필수다. 비우면 통과하지 못한다.
            assertThat(validator.validate(form)).anyMatch(v -> v.getPropertyPath().toString().equals("homeArea"));
            form.setHomeArea("서울 송파구");
            assertThat(validator.validate(form)).isEmpty();
            for (String password : new String[]{"Ab12345", "a".repeat(64), "12345678", "한글비밀번호123!", "Sample 123!", "Ab1!".repeat(17)}) {
                form.setPassword(password);
                form.setPasswordConfirm(password);
                assertThat(validator.validate(form)).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
            }
            form.setLoginId("a".repeat(51));
            form.setNickname("가".repeat(51));
            assertThat(validator.validate(form)).anyMatch(v -> v.getPropertyPath().toString().equals("loginId"))
                    .anyMatch(v -> v.getPropertyPath().toString().equals("nickname"));
            form.setLoginId("가나다라");
            form.setNickname(" ");
            assertThat(validator.validate(form)).anyMatch(v -> v.getPropertyPath().toString().equals("loginId"))
                    .anyMatch(v -> v.getPropertyPath().toString().equals("nickname"));
        }
    }
}
