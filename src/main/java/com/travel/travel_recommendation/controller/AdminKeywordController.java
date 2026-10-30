package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.service.AdminKeywordService;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
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

/** 관리자 키워드 관리 화면(UI-17). 조건 입력 선택지의 키워드·카테고리를 추가·수정·사용 중지한다. 삭제는 하지 않는다. */
@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/keywords")
public class AdminKeywordController {
    private final ObjectProvider<AdminKeywordService> services;

    @GetMapping
    String list(Principal principal, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        model.addAttribute("categories",service().catalog(login(principal)));
        return "admin/keywords";
    }

    @PostMapping
    String add(@RequestParam(required=false) Long categoryId, @RequestParam(defaultValue="") String code,
               @RequestParam(defaultValue="") String name, @RequestParam(defaultValue="") String sortOrder,
               Principal principal, RedirectAttributes redirect) {
        try {
            Long id=service().addKeyword(categoryId,code,name,sortOrder,login(principal));
            redirect.addFlashAttribute("adminMessage","키워드를 추가했어요. 조건 입력 화면의 선택지에 바로 나타나요.");
            return "redirect:/admin/keywords/"+id;
        } catch(ResponseStatusException | DataIntegrityViolationException error) {
            keepInput(redirect,error,name,sortOrder);
            redirect.addFlashAttribute("inputCategoryId",categoryId);
            redirect.addFlashAttribute("inputCode",code);
            return "redirect:/admin/keywords#add-title";
        }
    }

    @GetMapping("/{id}")
    String keyword(@PathVariable long id, Principal principal, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        model.addAttribute("detail",service().keyword(id,login(principal)));
        return "admin/keyword";
    }

    // 이름이 고정된 키워드는 화면에 이름 칸이 없으므로 name을 받지 않을 수도 있다.
    @PostMapping("/{id}")
    String update(@PathVariable long id, @RequestParam(required=false) String name,
                  @RequestParam(defaultValue="") String sortOrder, Principal principal, RedirectAttributes redirect) {
        try {
            boolean changed=service().updateKeyword(id,name,sortOrder,login(principal));
            redirect.addFlashAttribute("adminMessage",changed?"키워드를 고쳤어요. 조건 입력 화면에 바로 반영돼요.":"바뀐 내용이 없어요.");
        } catch(ResponseStatusException | DataIntegrityViolationException error) {
            keepInput(redirect,error,name,sortOrder);
        }
        return "redirect:/admin/keywords/"+id;
    }

    @PostMapping("/{id}/active")
    String changeActive(@PathVariable long id, @RequestParam boolean active, Principal principal,
                        RedirectAttributes redirect) {
        service().changeKeywordActive(id,active,login(principal));
        redirect.addFlashAttribute("adminMessage",active?"키워드를 다시 사용해요. 조건 입력 화면의 선택지에 다시 나타나요."
            :"키워드를 사용 중지했어요. 조건 입력 화면의 선택지에서 빠져요.");
        return "redirect:/admin/keywords/"+id;
    }

    @GetMapping("/categories/{id}")
    String category(@PathVariable long id, Principal principal, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        model.addAttribute("category",service().category(id,login(principal)));
        return "admin/keyword-category";
    }

    @PostMapping("/categories/{id}")
    String updateCategory(@PathVariable long id, @RequestParam(defaultValue="") String name,
                          @RequestParam(defaultValue="") String sortOrder, Principal principal, RedirectAttributes redirect) {
        try {
            boolean changed=service().updateCategory(id,name,sortOrder,login(principal));
            redirect.addFlashAttribute("adminMessage",changed?"카테고리를 고쳤어요.":"바뀐 내용이 없어요.");
        } catch(ResponseStatusException | DataIntegrityViolationException error) {
            keepInput(redirect,error,name,sortOrder);
        }
        return "redirect:/admin/keywords/categories/"+id;
    }

    @PostMapping("/categories/{id}/active")
    String changeCategoryActive(@PathVariable long id, @RequestParam boolean active, Principal principal,
                                RedirectAttributes redirect) {
        service().changeCategoryActive(id,active,login(principal));
        redirect.addFlashAttribute("adminMessage",active?"카테고리를 다시 사용해요. 사용 중인 키워드가 조건 입력 화면에 다시 나타나요."
            :"카테고리를 사용 중지했어요. 이 카테고리의 키워드가 조건 입력 화면에서 모두 빠져요.");
        return "redirect:/admin/keywords/categories/"+id;
    }

    // 입력 검증·중복 오류는 원래 화면으로 돌아가 알리고, 적은 값을 다시 채워 고쳐서 보낼 수 있게 한다.
    private void keepInput(RedirectAttributes redirect, RuntimeException error, String name, String sortOrder) {
        redirect.addFlashAttribute("keywordError",message(error));
        redirect.addFlashAttribute("inputName",name);
        redirect.addFlashAttribute("inputSortOrder",sortOrder);
    }

    @ExceptionHandler({ResponseStatusException.class,DataAccessException.class,TransactionException.class})
    String error(RuntimeException error, HttpServletResponse response, Model model) {
        int status=error instanceof ResponseStatusException e?e.getStatusCode().value()
            :error instanceof DataIntegrityViolationException?409:503;
        response.setStatus(status); response.setHeader("Cache-Control","no-store");
        model.addAttribute("message",message(error));
        return "admin/keyword-error";
    }
    private String message(RuntimeException error) {
        if(error instanceof ResponseStatusException e) return e.getReason();
        // 미리 중복을 확인해도 동시에 저장하면 DB의 UNIQUE 제약이 막을 수 있다.
        if(error instanceof DataIntegrityViolationException) return "같은 이름이나 코드가 이미 있어요. 목록을 다시 확인해 주세요.";
        return "키워드 정보를 처리하지 못했어요. 잠시 후 다시 시도해 주세요.";
    }
    private AdminKeywordService service() {
        var service=services.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"키워드 관리는 DB 연결 후 이용할 수 있어요.");
        return service;
    }
    private String login(Principal principal) { return principal==null?null:principal.getName(); }
}
