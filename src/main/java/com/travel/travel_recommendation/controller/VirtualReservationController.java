package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.dto.VirtualReservationView.CancelOutcome;
import com.travel.travel_recommendation.service.VirtualReservationService;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 가상 예약·결제 화면(SFR-13-01~07). 회원만 이용한다. 실제 시설 예약·금전 거래는 없다.
 * 예약 요청 키와 결제 멱등키는 화면을 열 때 서버가 만들어 폼에 넣으므로, 같은 폼을 다시 보내도 한 번만 처리된다.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/my/reservations")
public class VirtualReservationController {
    private final ObjectProvider<VirtualReservationService> services;

    @GetMapping
    String list(Principal principal, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        model.addAttribute("reservations",service().mine(principal.getName()));
        return "my/reservations";
    }

    /** 예약 신청 화면. 같은 장소의 다른 날짜를 고르면 그 날짜의 수량 구간으로 바꿔 보여 준다. */
    @GetMapping("/new")
    String form(@RequestParam long stockId, @RequestParam(required=false) Long itineraryId,
                @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
                Principal principal, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        var service=service();
        var stock=service.stockForBooking(stockId,principal.getName());
        if(date!=null && !date.equals(stock.usageStart().toLocalDate())) {
            var other=service.stockOn(stock.placeId(),date).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,
                date+"에는 이 장소의 가상 예약 수량이 없어요. 2026-10-01~2026-12-31 사이 날짜를 골라 주세요."));
            stock=service.stockForBooking(other.stockId(),principal.getName());
        }
        model.addAttribute("stock",stock);
        model.addAttribute("itineraryId",itineraryId);
        model.addAttribute("requestKey",UUID.randomUUID());
        model.addAttribute("maxRooms",VirtualReservationService.MAX_ROOMS);
        model.addAttribute("maxPeople",VirtualReservationService.MAX_PEOPLE);
        return "my/reservation-new";
    }

    @PostMapping
    String reserve(@RequestParam long stockId, @RequestParam(required=false) Long itineraryId,
                   @RequestParam(required=false) Integer quantity, @RequestParam(required=false) Integer partySize,
                   @RequestParam(required=false) UUID requestKey, Principal principal, RedirectAttributes redirect) {
        int people=partySize==null?0:partySize;
        int units=quantity==null?people:quantity;
        long id=service().reserve(principal.getName(),stockId,itineraryId,units,people,requestKey);
        redirect.addFlashAttribute("savedMessage","가상 예약을 신청했어요. 결제하면 예약이 확정돼요.");
        return "redirect:/my/reservations/"+id;
    }

    @GetMapping("/{id}")
    String detail(@PathVariable long id, Principal principal, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        model.addAttribute("reservation",service().detail(id,principal.getName()));
        model.addAttribute("paymentKey",UUID.randomUUID());
        model.addAttribute("maxRooms",VirtualReservationService.MAX_ROOMS);
        model.addAttribute("maxPeople",VirtualReservationService.MAX_PEOPLE);
        return "my/reservation";
    }

    @PostMapping("/{id}/pay")
    String pay(@PathVariable long id, @RequestParam(required=false) UUID idempotencyKey,
               @RequestParam(defaultValue="approve") String outcome, Principal principal, RedirectAttributes redirect) {
        var result=service().pay(principal.getName(),id,idempotencyKey,"decline".equals(outcome));
        String message="SUCCESS".equals(result.status())?"가상 결제가 완료돼 예약이 확정됐어요."
            :"FAILED".equals(result.status())?"가상 결제가 실패했어요(테스트). 예약은 결제 대기 상태로 남아 있어 다시 결제할 수 있어요."
            :"결제 상태를 확인해 주세요.";
        redirect.addFlashAttribute("savedMessage",result.replayed()?"이미 처리된 결제 요청이에요. "+message:message);
        return "redirect:/my/reservations/"+id;
    }

    @PostMapping("/{id}/change")
    String change(@PathVariable long id, @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
                  @RequestParam(required=false) Integer quantity, @RequestParam(required=false) Integer partySize,
                  Principal principal, RedirectAttributes redirect) {
        var service=service();
        var current=service.detail(id,principal.getName());
        if(current.stock()==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"예약한 수량 구간을 찾지 못했어요.");
        var target=date==null?current.stock():service.stockOn(current.stock().placeId(),date).orElseThrow(()->
            new ResponseStatusException(HttpStatus.NOT_FOUND,date+"에는 이 장소의 가상 예약 수량이 없어요."));
        int people=partySize==null?0:partySize;
        int units=quantity==null?people:quantity;
        service.change(principal.getName(),id,target.stockId(),units,people);
        redirect.addFlashAttribute("savedMessage","예약 내용을 바꾸고 금액을 다시 계산했어요.");
        return "redirect:/my/reservations/"+id;
    }

    @PostMapping("/{id}/cancel")
    String cancel(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        var outcome=service().cancel(principal.getName(),id);
        redirect.addFlashAttribute("savedMessage",outcome==CancelOutcome.REFUNDED?"예약을 취소하고 가상 결제를 환불 처리했어요."
            :outcome==CancelOutcome.CANCELED?"예약을 취소했어요.":"이미 취소된 예약이에요. 다시 처리하지 않았어요.");
        return "redirect:/my/reservations/"+id;
    }

    @ExceptionHandler({ResponseStatusException.class,DataAccessException.class})
    String error(RuntimeException error, HttpServletResponse response, Model model) {
        int status; String message;
        if(error instanceof ResponseStatusException e) { status=e.getStatusCode().value(); message=e.getReason(); }
        else { status=503; message="예약을 처리하지 못했어요. 바뀐 내용은 없어요. 잠시 후 다시 시도해 주세요."; }
        response.setStatus(status); response.setHeader("Cache-Control","no-store");
        model.addAttribute("message",message);
        return "my/reservation-error";
    }

    private VirtualReservationService service() {
        var service=services.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"가상 예약은 DB 연결 후 이용할 수 있어요.");
        return service;
    }
}
