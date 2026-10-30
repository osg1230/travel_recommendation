package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.service.FavoriteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 회원의 즐겨찾기 등록·목록·삭제 화면을 처리한다. */
@Controller
@RequiredArgsConstructor
@RequestMapping("/my/favorites")
public class FavoriteController {
    private final ObjectProvider<FavoriteService> services;

    @GetMapping
    String list(Principal principal, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        model.addAttribute("favorites",service().list(login(principal)));
        return "my/favorites";
    }

    @PostMapping
    String add(@RequestParam long placeId, @RequestParam(required=false) String returnTo,
               Principal principal, RedirectAttributes redirect) {
        boolean added;
        try {
            added=service().add(placeId,login(principal));
        } catch(DataIntegrityViolationException sameTime) {
            // 두 번 누르거나 다른 탭에서 거의 동시에 등록해 DB의 중복 방지에 걸린 경우다. 이미 등록된 것이므로 그렇게 안내한다.
            added=false;
        }
        redirect.addFlashAttribute("favoriteMessage",added?"즐겨찾기에 추가했어요.":"이미 즐겨찾기에 있는 장소예요.");
        return back(returnTo,redirect);
    }

    @PostMapping("/{placeId}/delete")
    String remove(@PathVariable long placeId, @RequestParam(required=false) String returnTo,
                  Principal principal, RedirectAttributes redirect) {
        var removed=service().remove(placeId,login(principal));
        if(removed.isEmpty()) {
            // 두 번 누르거나 다른 탭에서 먼저 지웠으면 이미 원하는 결과라 오류 대신 그렇게 안내한다.
            redirect.addFlashAttribute("favoriteMessage","이미 즐겨찾기에서 빠진 장소예요.");
        } else {
            redirect.addFlashAttribute("favoriteMessage","‘"+removed.get().placeName()+"’ 즐겨찾기를 삭제했어요.");
            // 즐겨찾기 목록에서는 방금 지운 장소를 다시 등록하는 '되돌리기' 버튼을 보여 준다(활성 장소만).
            if(removed.get().restorable()) redirect.addFlashAttribute("removedPlaceId",placeId);
        }
        return back(returnTo,redirect);
    }

    @ExceptionHandler({ResponseStatusException.class,DataAccessException.class,TransactionException.class})
    String error(RuntimeException error, HttpServletRequest request, HttpServletResponse response, Model model) {
        int status=400; String message;
        if(error instanceof ResponseStatusException e) { status=e.getStatusCode().value(); message=e.getReason(); }
        else { status=503; message="즐겨찾기를 처리하지 못했어요. 잠시 후 다시 시도해 주세요."; }
        response.setStatus(status); response.setHeader("Cache-Control","no-store");
        model.addAttribute("message",message);
        // 장소 후보·일정 상세에서 누르다 실패했으면 그 화면으로 돌아갈 링크를 둔다(즐겨찾기 목록 링크는 화면에 따로 있다).
        String returnTo=request.getParameter("returnTo");
        model.addAttribute("backUrl",returnTo!=null && RETURN_TO.matcher(returnTo).matches() && !returnTo.equals("/my/favorites")?returnTo:null);
        return "my/favorites-error";
    }
    // 입력된 주소로 그대로 이동하지 않고 이 서비스의 허용 경로만 사용한다.
    // 즐겨찾기 목록, 장소 후보 화면(#place-장소번호), 저장 일정 상세(#stop-일차-순번)만 받는다. #… 은 보던 항목 위치다.
    private static final Pattern RETURN_TO=Pattern.compile("^(/my/favorites"
        + "|/travel/recommendations/[0-9a-fA-F-]{36}/destinations/\\d+/places(#place-\\d+)?"
        + "|/my/itineraries/\\d+(#stop-\\d+-\\d+)?)$");
    /** 허용한 주소로 돌아간다. 항목 위치(#…)가 있으면 그 항목 옆에 안내를 보여 주도록 위치 이름을 함께 넘긴다. */
    private static String back(String returnTo, RedirectAttributes redirect) {
        if(returnTo==null || !RETURN_TO.matcher(returnTo).matches()) return "redirect:/my/favorites";
        if(returnTo.contains("#")) redirect.addFlashAttribute("favoriteAnchor",returnTo.substring(returnTo.indexOf('#')+1));
        return "redirect:"+returnTo;
    }
    private FavoriteService service() {
        var service=services.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"즐겨찾기는 DB 연결 후 이용할 수 있어요.");
        return service;
    }
    private String login(Principal principal) { return principal==null?null:principal.getName(); }
}
