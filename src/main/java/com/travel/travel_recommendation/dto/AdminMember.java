package com.travel.travel_recommendation.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 관리자 회원 관리 화면에 쓰는 최소 정보. 비밀번호 해시 등 인증정보는 담지 않는다. */
public final class AdminMember {
    private AdminMember() {}
    public record Summary(Long id, String loginId, String nickname, String role, String status,
                          String statusName, LocalDateTime joinedAt) {}
    public record StatusChange(LocalDateTime changedAt, String beforeStatus, String afterStatus,
                               String reason, String adminLoginId) {}
    public record Detail(Summary summary, LocalDateTime updatedAt, boolean statusChangeable,
                         String statusChangeBlockReason, List<StatusChange> history) {}
    public record Page(List<Summary> members, int page, int totalPages, long totalCount,
                       String keyword, String status) {}
}
