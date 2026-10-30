package com.travel.travel_recommendation;

import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.FavoriteService;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 즐겨찾기 등록·조회·삭제와 유형 제한을 실제 SQL Server에서 확인한다. */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
@Transactional
class FavoriteIntegrationTests {
    @Autowired FavoriteService favorites;
    @Autowired FavoriteRepository repository;
    @Autowired PlaceRepository places;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
    private User owner;
    private long attraction;
    private long restaurant;
    private long destination;

    @BeforeEach void setup() {
        owner=member();
        destination=places.findDestinations().getFirst().getId();
        var local=places.findLocalPlaces(places.findDestinations().getFirst().getRegion());
        attraction=local.stream().filter(p->"ATTRACTION".equals(p.getPlaceType())).findFirst().orElseThrow().getId();
        restaurant=local.stream().filter(p->"RESTAURANT".equals(p.getPlaceType())).findFirst().orElseThrow().getId();
    }
    private User member() {
        String suffix=UUID.randomUUID().toString().replace("-","");
        return users.saveAndFlush(new User("test_"+suffix,encoder.encode("Travel123!"),"여행_"+suffix));
    }

    @Test void registersOnceAndListsWithPlaceInformation() {
        assertThat(favorites.add(attraction,owner.getLoginId())).isTrue();
        assertThat(favorites.add(attraction,owner.getLoginId())).isFalse();
        assertThat(favorites.add(destination,owner.getLoginId())).isTrue();
        em.flush(); em.clear();
        var list=favorites.list(owner.getLoginId());
        assertThat(list).hasSize(2);
        assertThat(list).anySatisfy(f->{
            assertThat(f.placeId()).isEqualTo(destination);
            assertThat(f.typeName()).isEqualTo("여행지");
        });
        assertThat(favorites.registeredPlaceIds(owner.getLoginId())).containsExactlyInAnyOrder(attraction,destination);
        assertThat(favorites.registeredPlaceIds(null)).isEmpty();
    }

    @Test void refusesRestaurantInServiceAndDatabase() {
        assertThatThrownBy(()->favorites.add(restaurant,owner.getLoginId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("음식점");
        // 서비스를 지나쳐 직접 넣어도 DB 트리거가 막는다.
        assertThatThrownBy(()->jdbc.update("INSERT INTO dbo.favorites(user_id,place_id) VALUES(?,?)",
            owner.getId(),restaurant)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void keepsFavoritesSeparatePerMember() throws Exception {
        favorites.add(attraction,owner.getLoginId());
        em.flush(); em.clear();
        var other=member();
        assertThat(favorites.list(other.getLoginId())).isEmpty();
        mvc.perform(get("/my/favorites").with(user(other.getLoginId()))).andExpect(status().isOk())
            .andExpect(content().string(containsString("아직 등록한 즐겨찾기가 없어요")));
        // 다른 회원이 지우려 해도 자기 즐겨찾기에 없으니 지워지는 것이 없고, 오류 대신 '이미 빠진 장소'로 안내한다.
        mvc.perform(post("/my/favorites/"+attraction+"/delete").with(user(other.getLoginId())).with(csrf()))
            .andExpect(redirectedUrl("/my/favorites")).andExpect(flash().attribute("favoriteMessage",containsString("이미 즐겨찾기에서 빠진")));
        em.clear();
        assertThat(repository.existsByUserIdAndPlaceId(owner.getId(),attraction)).isTrue();
    }

    @Test void removesRegisteredPlace() throws Exception {
        favorites.add(attraction,owner.getLoginId());
        em.flush(); em.clear();
        String name=places.findById(attraction).orElseThrow().getName();
        mvc.perform(post("/my/favorites/"+attraction+"/delete").with(user(owner.getLoginId())).with(csrf()))
            .andExpect(redirectedUrl("/my/favorites"))
            .andExpect(flash().attribute("favoriteMessage","‘"+name+"’ 즐겨찾기를 삭제했어요."))
            .andExpect(flash().attribute("removedPlaceId",attraction));
        em.clear();
        assertThat(repository.existsByUserIdAndPlaceId(owner.getId(),attraction)).isFalse();
        // 두 번 눌러도 오류가 아니다.
        assertThat(favorites.remove(attraction,owner.getLoginId())).isEmpty();
        // 되돌리기는 같은 장소를 다시 등록한다.
        mvc.perform(post("/my/favorites").with(user(owner.getLoginId())).with(csrf()).param("placeId",String.valueOf(attraction)).param("returnTo","/my/favorites"))
            .andExpect(redirectedUrl("/my/favorites"));
        em.clear();
        assertThat(repository.existsByUserIdAndPlaceId(owner.getId(),attraction)).isTrue();
    }

    // 더 이상 확인되지 않는(비활성) 장소도 목록에 표시해 지울 수 있게 하고, 되돌리기는 두지 않는다.
    @Test void listsInactivePlaceSoItCanBeRemoved() throws Exception {
        favorites.add(attraction,owner.getLoginId());
        em.flush();
        jdbc.update("UPDATE dbo.places SET is_active=0 WHERE place_id=?",attraction);
        em.clear();
        assertThat(favorites.list(owner.getLoginId())).singleElement().satisfies(f->{
            assertThat(f.placeId()).isEqualTo(attraction);
            assertThat(f.active()).isFalse();
        });
        mvc.perform(get("/my/favorites").with(user(owner.getLoginId()))).andExpect(status().isOk())
            .andExpect(content().string(containsString("더 이상 확인되지 않는 장소예요")));
        assertThat(favorites.remove(attraction,owner.getLoginId())).hasValueSatisfying(r->assertThat(r.restorable()).isFalse());
        em.clear();
        assertThat(favorites.list(owner.getLoginId())).isEmpty();
    }
}
