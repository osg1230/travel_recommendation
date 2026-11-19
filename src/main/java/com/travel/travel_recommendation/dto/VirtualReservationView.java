package com.travel.travel_recommendation.dto;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Objects;

/** 가상 예약·결제(SFR-13) 화면 표시용 값. 실제 시설 예약·금전 거래와 연결되지 않는다. */
public final class VirtualReservationView {
    private VirtualReservationView() {}
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 이용 시작 시각(KST)이 지났는지. 시작한 예약은 결제·변경하지 않고, 확정된 예약은 취소·환불하지 않는다. */
    public static boolean started(LocalDateTime usageStart) {
        return usageStart != null && !usageStart.isAfter(LocalDateTime.now(KST));
    }

    /** 예약 가능 수량 한 구간. 이용 시각은 KST 현지 시각이다. */
    public record Stock(Long stockId, Long placeId, String placeName, String placeType, String address,
                        LocalDateTime usageStart, LocalDateTime usageEnd, int total, int remaining,
                        BigDecimal unitPrice, String unit, int maxPerUnit, String priceNote, String priceSourceUrl,
                        String roomType, String roomTypeName) {
        public boolean room() { return "ROOM".equals(unit); }
        public String unitName() { return room() ? "객실" : "명"; }
    }

    /** 저장 일정의 항목 중 같은 날짜에 가상 수량이 있는 장소. itemPlaceId는 일정표에서 그 항목을 찾는 데 쓴다. */
    public record Bookable(int dayNo, Long itemPlaceId, String itemTypeName, Stock stock, Long activeReservationId) {
        /** 이용 시작이 지난 날짜. 진행 중 예약이 없으면 '예약하기' 대신 지난 날짜라는 안내를 보여 준다. */
        public boolean past() { return started(stock.usageStart()); }
    }

    /** unitPrice는 예약(또는 마지막 변경) 당시 스냅샷에 남긴 1단위 가격이다. */
    public record Summary(Long id, String placeName, LocalDateTime usageStart, LocalDateTime usageEnd, int quantity,
                          int partySize, String unit, BigDecimal amount, String status, String paymentStatus,
                          LocalDateTime createdAt, String roomTypeName, BigDecimal unitPrice) {
        public String statusName() { return VirtualReservationView.statusName(status); }
        /** 결제 없이(또는 실패한 결제만 남기고) 취소된 예약은 '결제 없음', 결제한 뒤 취소했으면 '환불 완료'로 보여 준다. */
        public String paymentStatusName() {
            if ("CANCELED".equals(status) && !"REFUNDED".equals(paymentStatus)) return "결제 없음";
            return paymentStatus == null ? "결제 전" : VirtualReservationView.paymentName(paymentStatus);
        }
        public boolean started() { return VirtualReservationView.started(usageStart); }
        /** 목록의 '결제하기' 링크: 결제 대기이고 이용 시작 전일 때만. */
        public boolean payable() { return "PENDING".equals(status) && !started(); }
    }

    public record Payment(Long id, BigDecimal amount, String status, String failureCode,
                          LocalDateTime createdAt, LocalDateTime processedAt, LocalDateTime refundedAt) {
        public String statusName() { return paymentName(status); }
        /** 결제 실패 사유 코드를 화면용 한국어로 바꾼다. */
        public String failureName() {
            if (failureCode == null) return null;
            return switch (failureCode) {
                case "TEST_DECLINED" -> "테스트 결제에서 거절";
                case "RESERVATION_CANCELED" -> "결제 처리 전에 예약 취소";
                default -> "처리하지 못함";
            };
        }
    }

    public record Log(String eventType, int beforeQuantity, int delta, int afterQuantity,
                      String beforeStatus, String afterStatus, String reason, LocalDateTime createdAt) {
        /** 이력의 상태 칸: '결제 대기 → 취소됨'처럼 한국어로 보여 준다. */
        public String statusText() {
            return (beforeStatus == null ? "" : statusName(beforeStatus) + " → ") + statusName(afterStatus);
        }
        /**
         * 이력의 내용 칸. 2026-10-21 용어를 바꾸기 전에 남긴 이력은 '가상 예약 신청'처럼 '가상'이 붙어 있어,
         * DB 값은 그대로 두고 화면에서만 '예약 신청'으로 보여 준다(AGENTS.md 8절 용어).
         */
        public String reasonText() {
            return reason == null ? "" : reason.replace("가상 ", "").replace("가상", "");
        }
    }

    public record Detail(Summary summary, Long itineraryId, boolean paymentRequired, Stock stock,
                         List<Payment> payments, List<Log> logs, LocalDateTime canceledAt) {
        public boolean pending() { return "PENDING".equals(summary.status()); }
        public boolean confirmed() { return "CONFIRMED".equals(summary.status()); }
        public boolean canceled() { return "CANCELED".equals(summary.status()); }
        public boolean paid() { return payments.stream().anyMatch(p -> "SUCCESS".equals(p.status())); }
        public boolean started() { return summary.started(); }
        /** 결제 대기이고 이용 시작 전일 때만 결제한다. */
        public boolean payable() { return pending() && !started(); }
        /** 정의서 R3 규칙: PENDING 또는 결제 미사용 CONFIRMED만, 이용 시작 전에 일시·인원을 바꾼다. */
        public boolean changeable() { return (pending() || (confirmed() && !paymentRequired)) && !started(); }
        /** 결제 대기 예약은 언제든 취소하고, 확정된 예약은 이용 시작 전까지만 취소(환불)한다. */
        public boolean cancelable() { return pending() || (confirmed() && !started()); }

        /** 확정 시각: 승인된 결제의 처리 시각. 나중에 환불해도 처리 시각은 그대로 남는다. */
        public LocalDateTime confirmedAt() {
            return payments.stream().filter(p -> "SUCCESS".equals(p.status()) || "REFUNDED".equals(p.status()))
                .map(Payment::processedAt).filter(Objects::nonNull).findFirst().orElse(null);
        }

        /** 최근 변경 시각: 날짜·인원을 바꾼 마지막 이력의 시각. */
        public LocalDateTime changedAt() {
            return logs.stream().filter(l -> "CHANGE".equals(l.eventType())).map(Log::createdAt)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        }

        /** 지금 상태에서 할 수 있는 일과 다음 행동 안내. */
        public String guide() {
            if (canceled()) return "취소된 예약이에요. 다시 이용하려면 저장한 일정에서 새로 예약해 주세요.";
            if (pending()) return started()
                ? "이용 시작 시각이 지나 결제하거나 바꿀 수 없어요. 예약을 취소하면 잡아 둔 수량을 되돌려요."
                : "수량이 잡혀 있어요. 결제하면 확정돼요. 결제 전에는 날짜·인원을 바꾸거나 취소할 수 있어요.";
            if (started()) return paymentRequired ? "이용 시작 시각이 지나 취소·환불할 수 없어요." : "이용 시작 시각이 지나 취소할 수 없어요.";
            return paymentRequired
                ? "결제한 예약은 바꿀 수 없어요. 취소(환불) 후 다시 예약해 주세요."
                : "결제가 필요 없는 예약이라 바로 확정됐어요. 이용 전까지 날짜·인원을 바꾸거나 취소할 수 있어요.";
        }
    }

    public enum CancelOutcome { CANCELED, REFUNDED, ALREADY_CANCELED }

    public record PaymentResult(Long paymentId, String status, boolean replayed) {}

    public static String statusName(String status) {
        return switch (status) {
            case "PENDING" -> "결제 대기";
            case "CONFIRMED" -> "예약 확정";
            case "CANCELED" -> "취소됨";
            default -> status;
        };
    }

    public static String paymentName(String status) {
        return switch (status) {
            case "READY" -> "결제 처리 중";
            case "SUCCESS" -> "결제 완료";
            case "FAILED" -> "결제 실패";
            case "REFUNDED" -> "환불 완료";
            default -> status;
        };
    }
}
