package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.SavedItinerary;
import com.travel.travel_recommendation.dto.VirtualReservationView.*;
import com.travel.travel_recommendation.service.SavedItineraryService;
import com.travel.travel_recommendation.service.VirtualReservationService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.server.ResponseStatusException;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 예약 화면이 지금 무슨 일이 일어났는지, 무엇을 할 수 있는지, 어디로 돌아가면 되는지 알려 주는지 DB 없이 확인한다.
 * 예약 서비스는 흉내 낸다. 실제 수량·결제 처리는 VirtualReservationIntegrationTests가 SQL Server에서 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VirtualReservationControllerTests {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime FUTURE = LocalDateTime.now(KST).plusDays(10).withHour(15).withMinute(0).withSecond(0).withNano(0);
    private static final LocalDateTime PAST = LocalDateTime.now(KST).minusHours(3).withSecond(0).withNano(0);
    private static final BigDecimal PRICE = new BigDecimal("100000.00");
    @Autowired MockMvc mvc;
    @MockitoBean VirtualReservationService service;
    @MockitoBean SavedItineraryService itineraries;

    private static Stock room(long stockId, LocalDateTime start) {
        return new Stock(stockId,30L,"테스트 호텔","ACCOMMODATION","강원 강릉시 테스트로 1",start,start.plusHours(20),3,2,
            PRICE,"ROOM",2,"체험용 가격","https://example.com/price","STANDARD_DOUBLE","스탠다드 더블");
    }
    private static Summary summary(long id, String status, String payment, LocalDateTime start) {
        return new Summary(id,"테스트 호텔",start,start.plusHours(20),1,2,"ROOM",PRICE,status,payment,start.minusDays(5),"스탠다드 더블",PRICE);
    }
    private static Detail detail(long id, String status, LocalDateTime start, List<Payment> payments, List<Log> logs) {
        String latest=payments.isEmpty()?null:payments.get(payments.size()-1).status();
        return new Detail(summary(id,status,latest,start),7L,true,room(11L,start),payments,logs,
            "CANCELED".equals(status)?start.minusDays(1):null);
    }

    @Test void unexpectedFailuresShowTheServiceNoticeWithAWayBack() throws Exception {
        when(service.pay(eq("member"),eq(5L),any(),any(),anyBoolean())).thenThrow(new IllegalStateException("Reservation state changed while locked"));
        mvc.perform(post("/my/reservations/5/pay").with(user("member")).with(csrf())
                .param("idempotencyKey",UUID.randomUUID().toString()).param("expectedAmount","100000.00"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string(containsString("잠시 후 다시 시도해 주세요")))
            .andExpect(content().string(containsString("href=\"/my/reservations/5\"")))
            .andExpect(content().string(not(containsString("changed while locked"))));
        when(service.cancel("member",5L)).thenThrow(new TransactionTimedOutException("timeout"));
        mvc.perform(post("/my/reservations/5/cancel").with(user("member")).with(csrf()))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string(containsString("이전 화면으로")));
    }

    @Test void paymentNeedsTheAmountThatWasShownOnScreen() throws Exception {
        mvc.perform(post("/my/reservations/5/pay").with(user("member")).with(csrf()).param("idempotencyKey",UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("결제 금액 정보가 없어요")));
        verifyNoInteractions(service);
    }

    @Test void bookingErrorsReturnToTheFormOrGiveAWayBack() throws Exception {
        // 매진처럼 다시 고를 수 있는 거절은 신청 화면으로 돌아가 이유를 보여 주고 입력값을 다시 채운다.
        when(service.reserve(eq("member"),eq(12L),eq(7L),anyInt(),anyInt(),any()))
            .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,"이 날짜는 매진됐어요. 다른 날짜를 골라 주세요."));
        mvc.perform(post("/my/reservations").with(user("member")).with(csrf()).param("stockId","12").param("itineraryId","7")
                .param("quantity","1").param("partySize","2").param("requestKey",UUID.randomUUID().toString()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/my/reservations/new?stockId=12&itineraryId=7"))
            .andExpect(flash().attribute("reserveError","이 날짜는 매진됐어요. 다른 날짜를 골라 주세요."))
            .andExpect(flash().attribute("quantityInput",1))
            .andExpect(flash().attribute("partySizeInput",2));
        // 그 밖의 오류는 오류 화면에 '이전 화면으로'(신청 화면) 링크를 둔다.
        when(service.reserve(eq("member"),eq(11L),eq(7L),anyInt(),anyInt(),any()))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"연결할 내 일정을 찾지 못했어요."));
        mvc.perform(post("/my/reservations").with(user("member")).with(csrf()).param("stockId","11").param("itineraryId","7")
                .param("quantity","1").param("partySize","2").param("requestKey",UUID.randomUUID().toString()))
            .andExpect(status().isNotFound())
            .andExpect(content().string(containsString("연결할 내 일정을 찾지 못했어요.")))
            .andExpect(content().string(containsString("href=\"/my/reservations/new?stockId=11&amp;itineraryId=7\"")));
        // 신청 화면을 열지 못하면 일정으로 돌아가는 링크를 둔다.
        when(service.stockForBooking(13L,7L,"member"))
            .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,"이미 이용 시작 시각이 지난 날짜는 예약할 수 없어요."));
        mvc.perform(get("/my/reservations/new").param("stockId","13").param("itineraryId","7").with(user("member")))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("href=\"/my/itineraries/7\"")));
    }

    @Test void theFormRefillsInputAndMarksAnExistingReservation() throws Exception {
        var stock=room(11L,FUTURE);
        when(service.stockForBooking(11L,7L,"member")).thenReturn(stock);
        when(service.roomsForBooking(stock,7L)).thenReturn(List.of(stock));
        when(service.activeReservationOn("member",30L,FUTURE.toLocalDate())).thenReturn(Optional.of(9L));
        mvc.perform(get("/my/reservations/new").param("stockId","11").param("itineraryId","7").with(user("member"))
                .flashAttr("reserveError","객실당 최대 2명이에요. 객실 수를 늘려 주세요.").flashAttr("quantityInput",2).flashAttr("partySizeInput",5))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("객실당 최대 2명이에요. 객실 수를 늘려 주세요.")))
            .andExpect(content().string(containsString("value=\"2\"")))
            .andExpect(content().string(containsString("value=\"5\"")))
            .andExpect(content().string(containsString("이 날짜에 이미 예약이 있어요(예약번호 9)")))
            .andExpect(content().string(containsString("href=\"/my/reservations/9\"")))
            // 객실 가격·최대 인원은 미리보기 스크립트가 쓰고, 날짜 입력은 내일부터 1년 안으로 제한한다.
            .andExpect(content().string(containsString("data-max-people=\"2\"")))
            .andExpect(content().string(containsString("min=\""+LocalDate.now(KST).plusDays(1)+"\"")))
            .andExpect(content().string(containsString("max=\""+LocalDate.now(KST).plusDays(365)+"\"")));
    }

    @Test void changeTellsWhatChangedOrWhyNot() throws Exception {
        when(service.detail(5L,"member")).thenReturn(detail(5L,"PENDING",FUTURE,List.of(),List.of()));
        when(service.change("member",5L,11L,1,3)).thenReturn("인원 2→3명");
        mvc.perform(post("/my/reservations/5/change").with(user("member")).with(csrf()).param("quantity","1").param("partySize","3"))
            .andExpect(redirectedUrl("/my/reservations/5"))
            .andExpect(flash().attribute("savedMessage","예약을 바꿨어요: 인원 2→3명"));
        when(service.change("member",5L,11L,1,2)).thenReturn(null);
        mvc.perform(post("/my/reservations/5/change").with(user("member")).with(csrf()).param("quantity","1").param("partySize","2"))
            .andExpect(flash().attribute("savedMessage","바뀐 내용이 없어요. 예약은 그대로예요."));
        when(service.change("member",5L,11L,1,4)).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,"객실당 최대 2명이에요. 객실 수를 늘려 주세요."));
        mvc.perform(post("/my/reservations/5/change").with(user("member")).with(csrf()).param("quantity","1").param("partySize","4"))
            .andExpect(redirectedUrl("/my/reservations/5#change-title"))
            .andExpect(flash().attribute("changeError","객실당 최대 2명이에요. 객실 수를 늘려 주세요."))
            .andExpect(flash().attribute("partySizeInput",4));
        // 1년 넘는 날짜는 수량을 찾기 전에 이유를 알려 준다.
        var tooFar=LocalDate.now(KST).plusDays(400);
        mvc.perform(post("/my/reservations/5/change").with(user("member")).with(csrf())
                .param("date",tooFar.toString()).param("quantity","1").param("partySize","2"))
            .andExpect(redirectedUrl("/my/reservations/5#change-title"))
            .andExpect(flash().attribute("changeError","내일부터 1년 안의 날짜를 골라 주세요."))
            .andExpect(flash().attribute("dateInput",tooFar));
        verify(service,never()).sameOn(any(),eq(tooFar));
    }

    @Test void detailExplainsWhatCanBeDoneInEachState() throws Exception {
        var declined=new Payment(1L,PRICE,"FAILED","TEST_DECLINED",PAST.minusDays(2),PAST.minusDays(2),null);
        var approved=new Payment(2L,PRICE,"SUCCESS",null,PAST.minusDays(1),PAST.minusDays(1),null);
        var created=new Log("CREATE",3,-1,2,null,"PENDING","예약 신청",PAST.minusDays(3));
        // 이용 시작이 지난 결제 완료 예약: 취소·환불 버튼 대신 이유를 보여 준다.
        when(service.detail(5L,"member")).thenReturn(detail(5L,"CONFIRMED",PAST,List.of(declined,approved),List.of(created)));
        mvc.perform(get("/my/reservations/5").with(user("member")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("이용 시작 시각이 지나 취소·환불할 수 없어요.")))
            .andExpect(content().string(containsString("(이용 시작 시각 지남)")))
            .andExpect(content().string(not(containsString("취소하고 환불받기"))))
            .andExpect(content().string(containsString("결제 실패 (테스트 결제에서 거절)")))
            .andExpect(content().string(not(containsString("TEST_DECLINED"))))
            .andExpect(content().string(containsString("확정 시각")))
            .andExpect(content().string(containsString("100,000원 × 1실 = 100,000원")));
        // 이용 전 결제 완료 예약: 바꿀 수 없고 취소(환불) 후 다시 예약하라고 안내한다.
        when(service.detail(6L,"member")).thenReturn(detail(6L,"CONFIRMED",FUTURE,List.of(approved),List.of(created)));
        mvc.perform(get("/my/reservations/6").with(user("member")))
            .andExpect(content().string(containsString("결제한 예약은 바꿀 수 없어요. 취소(환불) 후 다시 예약해 주세요.")))
            .andExpect(content().string(containsString("취소하고 환불받기")))
            .andExpect(content().string(not(containsString("id=\"change-title\""))));
    }

    @Test void listOffersPaymentAndMarksPastDates() throws Exception {
        when(service.mine("member")).thenReturn(List.of(summary(1L,"PENDING",null,FUTURE),summary(2L,"PENDING",null,PAST),
            summary(3L,"CANCELED","FAILED",FUTURE)));
        mvc.perform(get("/my/reservations").with(user("member")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("href=\"/my/reservations/1#pay-title\"")))
            .andExpect(content().string(not(containsString("/my/reservations/2#pay-title"))))
            .andExpect(content().string(containsString("이용일 지남")))
            .andExpect(content().string(containsString("예약번호 3 · 결제 없음")));
    }

    @Test void itineraryShowsPastDatesAndReservationsLeftOut() throws Exception {
        var trip=new SavedItinerary.Summary(7L,"강릉 1박 일정","강릉",PAST.toLocalDate(),PAST.toLocalDate().plusDays(1),2,LocalDateTime.of(2026,9,20,1,0));
        var stay=new SavedItinerary.Item("STAY","숙박",LocalTime.of(15,0),LocalTime.of(23,0),"테스트 호텔","숙박","https://map","https://source",null,30L);
        when(itineraries.detail(7L,"member")).thenReturn(new SavedItinerary.Detail(trip,"자동차",
            List.of(new SavedItinerary.Day(1,PAST.toLocalDate(),List.of(stay)))));
        when(service.bookable(eq(7L),eq("member"),any())).thenReturn(List.of(new Bookable(1,30L,"숙박",room(11L,PAST),null)));
        when(service.leftoverReservations(eq(7L),eq("member"),any())).thenReturn(List.of(summary(9L,"PENDING",null,FUTURE)));
        mvc.perform(get("/my/itineraries/7").with(user("member")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("지난 날짜라 예약할 수 없어요")))
            .andExpect(content().string(not(containsString("/my/reservations/new"))))
            .andExpect(content().string(containsString("일정에서 빠진 장소의 예약")))
            .andExpect(content().string(containsString("href=\"/my/reservations/9\"")))
            .andExpect(content().string(containsString("연결된 예약은 취소되지 않고")));
    }
}
