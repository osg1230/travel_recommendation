package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ConfirmedTravelConditions;
import java.util.regex.Pattern;

/** 반려동물 동반 여행인지 판단한다. 여행 목적이나 처음 입력한 문장에 반려동물을 뜻하는 말이 있으면 동반 여행으로 본다. */
public final class PetTravel {
    private PetTravel() {}
    private static final Pattern PET = Pattern.compile("반려\\s?(견|동물|묘)|강아지|애견|댕댕이|멍멍이");

    public static boolean wanted(ConfirmedTravelConditions conditions) {
        return conditions != null && (mentions(conditions.travelPurpose()) || mentions(conditions.originalText()));
    }

    static boolean mentions(String text) { return text != null && PET.matcher(text).find(); }
}
