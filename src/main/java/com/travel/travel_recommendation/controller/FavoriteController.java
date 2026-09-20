package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.service.FavoriteService;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
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
        boolean added=service().add(placeId,login(principal));
        redirect.addFlashAttribute("favoriteMessage",added?"즐겨찾기에 추가했어요.":"이미 즐겨찾기에 있는 장소예요.");
        return "redirect:"+safeReturn(returnTo);
    }

    @PostMapping("/{placeId}/delete")
    String remove(@PathVariable long placeId, @RequestParam(required=false) String returnTo,
                  Principal principal, RedirectAttributes redirect) {
        service().remove(placeId,login(principal));
        redirect.addFlashAttribute("favoriteMessage","즐겨찾기에서 삭제했어요.");
        return "redirect:"+safeReturn(returnTo);
    }

    @ExceptionHandler({ResponseStatusException.class,DataAccessException.class})
    String error(RuntimeException error, HttpServletResponse response, Model model) {
        int status=400; String message;
        if(error instanceof ResponseStatusException e) { status=e.getStatusCode().value(); message=e.getReason(); }
        else { status=503; message="즐겨찾기를 처리하지 못했어요. 잠시 후 다시 시도해 주세요."; }
        response.setStatus(status); response.setHeader("Cache-Control","no-store");
        model.addAttribute("message",message);
        return "my/favorites-error";
    }
    // 입력된 주소로 그대로 이동하지 않고 이 서비스의 허용 경로만 사용한다.
    private String safeReturn(String returnTo) {
        if(returnTo==null || !returnTo.startsWith("/") || returnTo.startsWith("//")) return "/my/favorites";
        return returnTo.startsWith("/travel/recommendations/") || returnTo.startsWith("/my/favorites")
            ? returnTo : "/my/favorites";
    }
    private FavoriteService service() {
        var service=services.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"즐겨찾기는 DB 연결 후 이용할 수 있어요.");
        return service;
    }
    private String login(Principal principal) { return principal==null?null:principal.getName(); }
}
