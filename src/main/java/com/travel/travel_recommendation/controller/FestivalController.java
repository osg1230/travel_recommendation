package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.dto.RegionalFestival;
import com.travel.travel_recommendation.dto.TravelConditionForm;
import com.travel.travel_recommendation.dto.TravelKeyword;
import com.travel.travel_recommendation.service.FestivalService;
import com.travel.travel_recommendation.service.RecommendationException;
import com.travel.travel_recommendation.service.TravelConditionOptions;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 홈의 지역 축제에서 '이 축제 기간으로 일정 만들기'를 누르면 조건 입력 화면의 지역·날짜를 미리 채운다. */
@Controller
@RequiredArgsConstructor
public class FestivalController {
    private final FestivalService festivalService;
    private final TravelConditionOptions options;

    @PostMapping("/travel/conditions/festival")
    String startFromFestival(@RequestParam String province, @RequestParam String festivalId,
                             HttpSession session, RedirectAttributes redirect) {
        var selected = FestivalService.province(province).orElse(null);
        if (selected == null) return "redirect:/";
        String back = "redirect:/?festivalRegion=" + selected.code() + "#festivals";
        // 화면에서 보낸 날짜 대신 서버가 다시 찾은 축제의 날짜를 쓴다.
        Optional<RegionalFestival> festival;
        try {
            festival = festivalService.findFestival(selected, festivalId);
        } catch (RecommendationException e) {
            redirect.addFlashAttribute("festivalError", e.getMessage());
            return back;
        }
        if (festival.isEmpty()) {
            redirect.addFlashAttribute("festivalError", "고른 축제를 다시 찾지 못했어요. 목록을 새로 확인해 주세요.");
            return back;
        }
        var f = festival.get();
        // 이미 시작한 축제는 오늘부터, 아니면 축제 첫날부터 1박 2일로 채운다. 기간은 입력 화면에서 바꿀 수 있다.
        LocalDate start = f.startDate().isBefore(festivalService.today()) ? festivalService.today() : f.startDate();
        var form = new TravelConditionForm();
        form.setInputMode("KEYWORD");
        form.setRegion(regionValue(selected.regionName()));
        form.setStartDate(start.toString());
        form.setEndDate(start.plusDays(1).toString());
        // 입력 화면의 '1박 2일'이 선택된 상태로 보이도록 여행 일수도 날짜와 같게 채운다.
        form.setDurationDays("2");
        synchronized (session) {
            // 미리 채운 값일 뿐이며 조건 입력에서 다시 제출해 검증을 거쳐야 초안이 된다.
            session.setAttribute(TravelConditionController.PREFILL_KEY, form);
            session.removeAttribute(TravelConditionReviewController.REVIEW_KEY);
            session.removeAttribute(TravelConditionReviewController.CONFIRMED_KEY);
        }
        var dates = DateTimeFormatter.ofPattern("M월 d일");
        redirect.addFlashAttribute("festivalMessage", f.title() + "(" + f.startDate().format(dates) + "~" + f.endDate().format(dates)
            + ")에 맞춰 " + (form.getRegion().isEmpty() ? "" : selected.label() + " 지역과 ") + "날짜를 채웠어요. "
            + "축제 장소는 일정에 자동으로 들어가지 않으니, 필요하면 조건을 바꾼 뒤 추천받아 주세요.");
        return "redirect:/travel/conditions";
    }

    /** 조건 입력 화면의 지역 선택값(DB 키워드 ID)을 찾는다. 목록을 못 읽으면 지역은 비워 두고 날짜만 채운다. */
    private String regionValue(String regionName) {
        try {
            List<TravelKeyword> regions = options.getChoices().get("region");
            return regions.stream().filter(k -> k.name().equals(regionName)).map(TravelKeyword::getSelectionValue).findFirst().orElse("");
        } catch (DataAccessException e) {
            return "";
        }
    }
}
