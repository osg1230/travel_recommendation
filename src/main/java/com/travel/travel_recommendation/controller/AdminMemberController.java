package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.service.AdminMemberService;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 관리자 회원 관리 화면(UI-18). 검색·목록, 상세 조회, 상태 변경만 제공한다. */
@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/members")
public class AdminMemberController {
    private final ObjectProvider<AdminMemberService> services;

    @GetMapping
    String list(@RequestParam(required=false) String keyword, @RequestParam(required=false) String status,
                @RequestParam(defaultValue="0") int page, Principal principal,
                HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        model.addAttribute("members",service().search(keyword,status,page,login(principal)));
        return "admin/members";
    }

    @GetMapping("/{id}")
    String detail(@PathVariable long id, Principal principal, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        model.addAttribute("member",service().detail(id,login(principal)));
        return "admin/member";
    }

    @PostMapping("/{id}/status")
    String changeStatus(@PathVariable long id, @RequestParam String status, @RequestParam String reason,
                        Principal principal, RedirectAttributes redirect) {
        try {
            service().changeStatus(id,status,reason,login(principal));
        } catch(ResponseStatusException | DataAccessException | TransactionException error) {
            // 회원 화면으로 돌아가 오류를 알리고, 적은 사유를 다시 채워 고쳐서 보낼 수 있게 한다.
            redirect.addFlashAttribute("statusError",message(error));
            redirect.addFlashAttribute("statusReason",reason);
            return "redirect:/admin/members/"+id;
        }
        redirect.addFlashAttribute("adminMessage","회원 상태를 바꾸고 변경 사유를 기록했어요.");
        return "redirect:/admin/members/"+id;
    }

    @ExceptionHandler({ResponseStatusException.class,DataAccessException.class,TransactionException.class})
    String error(RuntimeException error, HttpServletResponse response, Model model) {
        int status=error instanceof ResponseStatusException e?e.getStatusCode().value():503;
        response.setStatus(status); response.setHeader("Cache-Control","no-store");
        model.addAttribute("message",message(error));
        return "admin/member-error";
    }
    private String message(RuntimeException error) {
        return error instanceof ResponseStatusException e?e.getReason():"회원 정보를 처리하지 못했어요. 잠시 후 다시 시도해 주세요.";
    }
    private AdminMemberService service() {
        var service=services.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"회원 관리는 DB 연결 후 이용할 수 있어요.");
        return service;
    }
    private String login(Principal principal) { return principal==null?null:principal.getName(); }
}
