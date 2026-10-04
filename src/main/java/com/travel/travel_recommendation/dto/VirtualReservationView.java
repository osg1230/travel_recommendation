package com.travel.travel_recommendation.dto;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** 가상 예약·결제(SFR-13) 화면 표시용 값. 실제 시설 예약·금전 거래와 연결되지 않는다. */
public final class VirtualReservationView {
    private VirtualReservationView() {}

    /** 예약 가능 수량 한 구간. 이용 시각은 KST 현지 시각이다. */
    public record Stock(Long stockId, Long placeId, String placeName, String placeType, String address,
                        LocalDateTime usageStart, LocalDateTime usageEnd, int total, int remaining,
                        BigDecimal unitPrice, String unit, int maxPerUnit, String priceNote, String priceSourceUrl) {
        public boolean room() { return "ROOM".equals(unit); }
        public String unitName() { return room() ? "객실" : "명"; }
    }

    /** 저장 일정의 항목 중 같은 날짜에 가상 수량이 있는 장소. itemPlaceId는 일정표에서 그 항목을 찾는 데 쓴다. */
    public record Bookable(int dayNo, Long itemPlaceId, String itemTypeName, Stock stock, Long activeReservationId) {}

    public record Summary(Long id, String placeName, LocalDateTime usageStart, LocalDateTime usageEnd, int quantity,
                          int partySize, String unit, BigDecimal amount, String status, String paymentStatus,
                          LocalDateTime createdAt) {
        public String statusName() { return VirtualReservationView.statusName(status); }
        public String paymentStatusName() { return paymentStatus == null ? "결제 전" : VirtualReservationView.paymentName(paymentStatus); }
    }

    public record Payment(Long id, BigDecimal amount, String status, String failureCode,
                          LocalDateTime createdAt, LocalDateTime processedAt, LocalDateTime refundedAt) {
        public String statusName() { return paymentName(status); }
    }

    public record Log(String eventType, int beforeQuantity, int delta, int afterQuantity,
                      String beforeStatus, String afterStatus, String reason, LocalDateTime createdAt) {}

    public record Detail(Summary summary, Long itineraryId, boolean paymentRequired, Stock stock,
                         List<Payment> payments, List<Log> logs) {
        public boolean pending() { return "PENDING".equals(summary.status()); }
        public boolean canceled() { return "CANCELED".equals(summary.status()); }
        public boolean paid() { return payments.stream().anyMatch(p -> "SUCCESS".equals(p.status())); }
        /** 정의서 R3 규칙: PENDING 또는 결제 미사용 CONFIRMED만 일시·인원을 바꾼다. */
        public boolean changeable() { return pending() || ("CONFIRMED".equals(summary.status()) && !paymentRequired); }
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
