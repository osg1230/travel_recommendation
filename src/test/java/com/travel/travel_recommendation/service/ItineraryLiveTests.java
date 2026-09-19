package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.KakaoMapProperties;
import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_KAKAO_ITINERARY_TESTS",matches="true")
class ItineraryLiveTests {
    @Test void generatesTwoDaysWithRealRoutesAndSharedHotelAddress() {
        var maps=new KakaoMapClient(new KakaoMapProperties(System.getenv("KAKAO_REST_API_KEY")),new JsonMapper());
        var generator=new ItineraryGenerator(maps);
        var verified=LocalDateTime.of(2026,9,17,15,25);
        var attraction1=place(1,"경포대","강원특별자치도 강릉시 경포로 365",verified);
        var attraction2=place(2,"오죽헌·시립박물관","강원특별자치도 강릉시 율곡로3139번길 24",verified);
        var hotel=place(3,"씨마크 호텔","강원특별자치도 강릉시 해안로406번길 2",verified);
        var restaurant=place(4,"씨마크 호텔 더 레스토랑","강원특별자치도 강릉시 해안로406번길 2 씨마크 호텔 1층",verified);
        var id=UUID.randomUUID();
        var pool=new PlaceCandidateView(id,new DestinationCandidate(10L,"강릉","강원특별자치도 강릉시","소개","https://www.gn.go.kr/",verified),2,true,
            List.of(attraction1,attraction2),List.of(hotel),List.of(restaurant));
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",null,null,2,null,2,true,false,List.of(),"");
        var input=new Input(id,pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
        var result=generator.generate(input);
        assertThat(result.days()).hasSize(2);
        assertThat(result.days().getFirst().items().getLast().type()).isEqualTo("STAY");
        assertThat(result.days().stream().flatMap(d->d.items().stream()).filter(i->i.travelSeconds()!=null))
            .hasSize(3).allSatisfy(item->assertThat(item.travelSeconds()).isPositive());
        generator.validate(input,result);
    }
    private PlaceCandidate place(long id,String name,String address,LocalDateTime verified) {
        return new PlaceCandidate(id,name,address,"테스트","공식 초기 장소 주소의 연동 검사","https://www.gn.go.kr/","https://map.kakao.com/",verified);
    }
}
