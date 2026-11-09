package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.entity.Favorite;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.entity.PlaceRating;
import com.travel.travel_recommendation.repository.FavoriteRepository;
import com.travel.travel_recommendation.repository.PlaceRatingRepository;
import com.travel.travel_recommendation.repository.PlaceRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MyTasteServiceTests {
    private final FavoriteRepository favorites=mock(FavoriteRepository.class);
    private final PlaceRatingRepository ratings=mock(PlaceRatingRepository.class);
    private final PlaceRepository places=mock(PlaceRepository.class);
    private final MyTasteService service=new MyTasteService(favorites,ratings,places);

    private static Place place(String type,String name,String subtype) {
        var place=mock(Place.class);
        when(place.getPlaceType()).thenReturn(type);
        when(place.getName()).thenReturn(name);
        when(place.getSubtype()).thenReturn(subtype);
        return place;
    }

    private static Favorite favorite(long placeId) {
        var favorite=mock(Favorite.class);
        when(favorite.getPlaceId()).thenReturn(placeId);
        return favorite;
    }

    private static PlaceRating rating(long placeId,int score) {
        var rating=mock(PlaceRating.class);
        when(rating.getPlaceId()).thenReturn(placeId);
        when(rating.getScore()).thenReturn((short)score);
        return rating;
    }

    // 비회원은 기록을 읽지 않고 빈 값이다.
    @Test void guestsHaveNoTaste() {
        assertThat(service.themeCounts(null)).isEmpty();
        verifyNoInteractions(favorites,ratings,places);
    }

    // 즐겨찾기와 4점 이상 평가를 합치고, 둘 다에 있는 곳은 한 번만 읽는다. 3점 평가는 좋아한 곳으로 보지 않는다.
    @SuppressWarnings("unchecked")
    @Test void joinsFavoritesAndHighRatingsOnce() {
        // 목록을 먼저 만든다. when(...) 안에서 다른 mock을 만들면 Mockito 스텁이 끝나지 않는다.
        var liked=List.of(favorite(1),favorite(2));
        var rated=List.of(rating(2,5),rating(3,4),rating(4,3));
        when(favorites.findByUserIdOrderByIdDesc(7L)).thenReturn(liked);
        when(ratings.findByUserIdOrderByUpdatedAtDescIdDesc(7L)).thenReturn(rated);
        when(places.findAllById(any())).thenReturn(List.of());
        service.themeCounts(7L);
        var ids=org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(places).findAllById(ids.capture());
        assertThat((Iterable<Long>)ids.getValue()).containsExactly(1L,2L,3L);
    }

    @Test void noRecordsMeansNoTaste() {
        when(favorites.findByUserIdOrderByIdDesc(7L)).thenReturn(List.of());
        var rated=List.of(rating(4,2));
        when(ratings.findByUserIdOrderByUpdatedAtDescIdDesc(7L)).thenReturn(rated);
        assertThat(service.themeCounts(7L)).isEmpty();
        verifyNoInteractions(places);
    }

    // 관광지만 센다. 숙소·여행지·음식점과 어느 종류에도 맞지 않는 곳은 빠지고, 두 종류에 맞는 곳(수목원: 자연·휴식)은 둘 다 센다.
    @Test void countsOnlyAttractionsByTheme() {
        var counts=MyTasteService.countThemes(List.of(
            place("ATTRACTION","대관령 수목원","여행 > 관광,명소 > 수목원,식물원"),
            place("ATTRACTION","소금강 계곡","여행 > 관광,명소 > 계곡"),
            place("ATTRACTION","경포해변","여행 > 관광,명소 > 해수욕장,해변"),
            place("ATTRACTION","강릉중앙시장","쇼핑 > 시장"),
            place("STAY","바다 호텔","숙박 > 호텔"),
            place("DESTINATION","강릉","자연관광"),
            place("RESTAURANT","숲속 식당","음식점 > 한식")));
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("자연",2,"휴식",1,"바다",1));
    }
}
