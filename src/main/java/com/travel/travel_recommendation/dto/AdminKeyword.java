package com.travel.travel_recommendation.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 관리자 키워드 관리 화면(UI-17)에 쓰는 정보. */
public final class AdminKeyword {
    private AdminKeyword() {}
    /** nameLockReason이 null이면 이름을 고칠 수 있고, 아니면 고칠 수 없는 이유를 담는다. */
    public record Item(Long id, String code, String name, int sortOrder, boolean active, String nameLockReason) {}
    /** addLockReason이 null이면 이 카테고리에 새 키워드를 추가할 수 있다. */
    public record Category(Long id, String code, String name, int sortOrder, boolean active,
                           String addLockReason, List<Item> keywords) {}
    public record KeywordDetail(Item keyword, String categoryName, boolean categoryActive, LocalDateTime updatedAt) {}
    public record CategoryDetail(Long id, String code, String name, int sortOrder, boolean active,
                                 long keywordCount, LocalDateTime updatedAt) {}
}
