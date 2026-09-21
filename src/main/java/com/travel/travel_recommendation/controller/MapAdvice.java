package com.travel.travel_recommendation.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** 지도 화면이 쓰는 카카오 JavaScript 키를 전달한다. 키가 없거나 형식이 다르면 지도 대신 안내를 보여 준다. */
@ControllerAdvice
public class MapAdvice {
    private final String key;

    public MapAdvice(@Value("${travel.kakao.javascript-key:}") String key) {
        // 스크립트 주소에 그대로 들어가므로 영문·숫자로 된 키만 받는다.
        this.key = key != null && key.matches("[A-Za-z0-9]{16,64}") ? key : "";
    }

    @ModelAttribute("kakaoMapKey")
    String kakaoMapKey() { return key; }
}
