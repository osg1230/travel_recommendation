package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.dto.VirtualReservationView.CancelOutcome;
import com.travel.travel_recommendation.dto.VirtualReservationView.Stock;
import com.travel.travel_recommendation.service.VirtualReservationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 예약·결제 화면(SFR-13-01~07). 회원만 이용한다. 실제 시설 예약·금전 거래는 없다.
 * 예약 요청 키와 결제 멱등키는 화면을 열 때 서버가 만들어 폼에 넣으므로, 같은 폼을 다시 보내도 한 번만 처리된다.
 * 신청·변경의 입력 오류(400)와 매진(409)은 오류 화면 대신 원래 화면으로 돌아가 이유를 보여 주고 입력값을 다시 채운다.
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

    /**
     * 예약 신청 화면. 저장한 일정(itineraryId)에 있는 장소만 연다.
     * 같은 장소의 다른 날짜를 고르면 그 날짜의 수량 구간으로 바꿔 보여 주고, 수량이 없으면 원래 화면으로 돌아가 이유를 알려 준다.
     */
    @GetMapping("/new")
    String form(@RequestParam long stockId, @RequestParam(required=false) Long itineraryId,
                @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
                Principal principal, HttpServletResponse response, Model model, RedirectAttributes redirect) {
        response.setHeader("Cache-Control","no-store");
        var service=service();
        var stock=service.stockForBooking(stockId,itineraryId,principal.getName());
        if(date!=null && !date.equals(stock.usageStart().toLocalDate())) {
            boolean inWindow=VirtualReservationService.withinBookingWindow(date);
            Optional<Stock> other=inWindow?service.sameOnInItinerary(stock,itineraryId,date):Optional.empty();
            if(other.isEmpty()) {
                redirect.addFlashAttribute("dateError",inWindow
                    ?date+"에는 이 장소의 예약 수량이 없어요. 일정에 있는 날짜를 골라 주세요."
                    :"내일부터 1년 안의 날짜를 골라 주세요.");
                return "redirect:"+formUrl(stockId,itineraryId);
            }
            stock=service.stockForBooking(other.get().stockId(),itineraryId,principal.getName());
        }
        model.addAttribute("stock",stock);
        // 숙소는 객실 종류를 고를 수 있게 그 날짜의 객실 목록을 함께 보여 준다.
        model.addAttribute("rooms",stock.room()?service.roomsForBooking(stock,itineraryId):List.of());
        model.addAttribute("itineraryId",itineraryId);
        // 뒤로 가기나 다른 탭으로 같은 장소·날짜를 다시 신청하는 경우를 알려 준다. 객실을 더 잡을 수도 있어 막지는 않는다.
        model.addAttribute("existingReservationId",
            service.activeReservationOn(principal.getName(),stock.placeId(),stock.usageStart().toLocalDate()).orElse(null));
        model.addAttribute("requestKey",UUID.randomUUID());
        model.addAttribute("maxRooms",VirtualReservationService.MAX_ROOMS);
        model.addAttribute("maxPeople",VirtualReservationService.MAX_PEOPLE);
        addDateRange(model,stock.usageStart().toLocalDate());
        return "my/reservation-new";
    }

    @PostMapping
    String reserve(@RequestParam long stockId, @RequestParam(required=false) Long itineraryId,
                   @RequestParam(required=false) Integer quantity, @RequestParam(required=false) Integer partySize,
                   @RequestParam(required=false) UUID requestKey, Principal principal, RedirectAttributes redirect) {
        int people=partySize==null?0:partySize;
        int units=quantity==null?people:quantity;
        try {
            long id=service().reserve(principal.getName(),stockId,itineraryId,units,people,requestKey);
            redirect.addFlashAttribute("savedMessage","예약을 신청했어요. 수량이 잡혀 있으니 결제하면 예약이 확정돼요.");
            return "redirect:/my/reservations/"+id;
        } catch(ResponseStatusException rejected) {
            if(!fixable(rejected)) throw rejected;
            // 인원 초과·매진처럼 고칠 수 있는 문제는 신청 화면으로 돌아가 이유를 보여 주고, 입력한 값을 다시 채운다.
            redirect.addFlashAttribute("reserveError",rejected.getReason());
            if(quantity!=null) redirect.addFlashAttribute("quantityInput",quantity);
            if(partySize!=null) redirect.addFlashAttribute("partySizeInput",partySize);
            return "redirect:"+formUrl(stockId,itineraryId);
        }
    }

    @GetMapping("/{id}")
    String detail(@PathVariable long id, Principal principal, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        var reservation=service().detail(id,principal.getName());
        model.addAttribute("reservation",reservation);
        model.addAttribute("paymentKey",UUID.randomUUID());
        model.addAttribute("maxRooms",VirtualReservationService.MAX_ROOMS);
        model.addAttribute("maxPeople",VirtualReservationService.MAX_PEOPLE);
        addDateRange(model,reservation.summary().usageStart().toLocalDate());
        return "my/reservation";
    }

    /** expectedAmount는 결제 버튼에 보였던 금액이다. 그 사이 금액이 바뀌었으면 결제하지 않는다. */
    @PostMapping("/{id}/pay")
    String pay(@PathVariable long id, @RequestParam(required=false) UUID idempotencyKey,
               @RequestParam(required=false) BigDecimal expectedAmount,
               @RequestParam(defaultValue="approve") String outcome, Principal principal, RedirectAttributes redirect) {
        if(expectedAmount==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"결제 금액 정보가 없어요. 예약 화면을 다시 열어 주세요.");
        var result=service().pay(principal.getName(),id,idempotencyKey,expectedAmount,"decline".equals(outcome));
        String message="SUCCESS".equals(result.status())?"결제가 완료돼 예약이 확정됐어요."
            :"FAILED".equals(result.status())?"결제가 실패했어요(테스트). 예약은 결제 대기 상태로 남아 있어 다시 결제할 수 있어요."
            :"결제 상태를 확인해 주세요.";
        redirect.addFlashAttribute("savedMessage",result.replayed()?"이미 처리된 결제 요청이에요. "+message:message);
        return "redirect:/my/reservations/"+id;
    }

    /** 바뀐 내용을 완료 문구로 알려 준다. 고칠 수 있는 오류는 예약 상세의 변경 칸으로 돌아가 입력값을 다시 채운다. */
    @PostMapping("/{id}/change")
    String change(@PathVariable long id, @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
                  @RequestParam(required=false) Integer quantity, @RequestParam(required=false) Integer partySize,
                  Principal principal, RedirectAttributes redirect) {
        var service=service();
        var current=service.detail(id,principal.getName());
        if(current.stock()==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"예약한 수량 구간을 찾지 못했어요.");
        int people=partySize==null?0:partySize;
        int units=quantity==null?people:quantity;
        try {
            var target=current.stock();
            if(date!=null && !date.equals(target.usageStart().toLocalDate())) {
                if(!VirtualReservationService.withinBookingWindow(date))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"내일부터 1년 안의 날짜를 골라 주세요.");
                target=service.sameOn(current.stock(),date).orElseThrow(()->
                    new ResponseStatusException(HttpStatus.BAD_REQUEST,date+"에는 이 장소의 예약 수량이 없어요. 다른 날짜를 골라 주세요."));
            }
            String changes=service.change(principal.getName(),id,target.stockId(),units,people);
            redirect.addFlashAttribute("savedMessage",changes==null?"바뀐 내용이 없어요. 예약은 그대로예요."
                :"예약을 바꿨어요: "+changes);
            return "redirect:/my/reservations/"+id;
        } catch(ResponseStatusException rejected) {
            if(!fixable(rejected)) throw rejected;
            redirect.addFlashAttribute("changeError",rejected.getReason());
            if(date!=null) redirect.addFlashAttribute("dateInput",date);
            if(quantity!=null) redirect.addFlashAttribute("quantityInput",quantity);
            if(partySize!=null) redirect.addFlashAttribute("partySizeInput",partySize);
            return "redirect:/my/reservations/"+id+"#change-title";
        }
    }

    @PostMapping("/{id}/cancel")
    String cancel(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        var service=service();
        var outcome=service.cancel(principal.getName(),id);
        String message=switch(outcome) {
            case REFUNDED -> "예약을 취소하고 결제를 환불 처리했어요. 잡아 둔 수량도 되돌렸어요.";
            case CANCELED -> "예약을 취소했어요. 잡아 둔 수량을 되돌렸어요.";
            // 연속 클릭·다른 탭: 다시 처리하지 않고 지금 결제 상태(환불 완료·결제 없음)를 함께 알려 준다.
            case ALREADY_CANCELED -> "이미 취소된 예약이에요("+service.detail(id,principal.getName()).summary().paymentStatusName()
                +"). 다시 처리하지 않았어요.";
        };
        redirect.addFlashAttribute("savedMessage",message);
        return "redirect:/my/reservations/"+id;
    }

    /** 처리하지 못한 오류는 이유와 함께 '이전 화면으로' 링크를 보여 준다. DB·트랜잭션 문제는 503 안내로 보낸다. */
    @ExceptionHandler({ResponseStatusException.class,DataAccessException.class,TransactionException.class,IllegalStateException.class})
    String error(RuntimeException error, HttpServletRequest request, HttpServletResponse response, Model model) {
        int status; String message;
        if(error instanceof ResponseStatusException e) { status=e.getStatusCode().value(); message=e.getReason(); }
        else { status=503; message="예약을 처리하지 못했어요. 바뀐 내용은 없어요. 잠시 후 다시 시도해 주세요."; }
        response.setStatus(status); response.setHeader("Cache-Control","no-store");
        model.addAttribute("message",message);
        model.addAttribute("backUrl",backUrl(request));
        return "my/reservation-error";
    }

    /** 입력을 고치거나 다른 날짜·객실을 골라 다시 시도할 수 있는 거절(400 입력 오류, 409 매진·상태 변경). */
    private static boolean fixable(ResponseStatusException rejected) {
        return rejected.getStatusCode()==HttpStatus.BAD_REQUEST || rejected.getStatusCode()==HttpStatus.CONFLICT;
    }

    private static String formUrl(long stockId, Long itineraryId) {
        return "/my/reservations/new?stockId="+stockId+(itineraryId==null?"":"&itineraryId="+itineraryId);
    }

    private static final Pattern RESERVATION_ACTION=Pattern.compile("^/my/reservations/(\\d+)/(pay|change|cancel)$");

    /**
     * 오류 화면의 '이전 화면으로' 주소. 예약 상세에서 한 작업이면 그 상세로, 신청이면 신청 화면으로,
     * 신청 화면을 열지 못했으면 일정으로 돌려보낸다. 예약 목록으로 가는 링크는 화면에 따로 있다.
     */
    private static String backUrl(HttpServletRequest request) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        var action=RESERVATION_ACTION.matcher(path);
        if(action.matches()) return "/my/reservations/"+action.group(1);
        Long stockId=number(request.getParameter("stockId")), itineraryId=number(request.getParameter("itineraryId"));
        if(path.equals("/my/reservations") && "POST".equals(request.getMethod()) && stockId!=null) return formUrl(stockId,itineraryId);
        if(path.equals("/my/reservations/new")) return itineraryId==null?"/my/itineraries":"/my/itineraries/"+itineraryId;
        return null;
    }

    private static Long number(String text) {
        try { return text==null?null:Long.valueOf(text); } catch(NumberFormatException e) { return null; }
    }

    /**
     * 날짜 입력 범위(min=내일, max=1년 뒤). 서버가 수량을 만들어 주는 범위와 같다.
     * 지금 고른 날짜가 범위 밖이어도(오늘 이용 등) 그대로 보낼 수 있게 범위에 넣는다.
     */
    private static void addDateRange(Model model, LocalDate current) {
        LocalDate first=VirtualReservationService.firstBookableDate(), last=VirtualReservationService.lastBookableDate();
        model.addAttribute("minDate",current.isBefore(first)?current:first);
        model.addAttribute("maxDate",current.isAfter(last)?current:last);
    }

    private VirtualReservationService service() {
        var service=services.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"예약은 DB 연결 후 이용할 수 있어요.");
        return service;
    }
}
