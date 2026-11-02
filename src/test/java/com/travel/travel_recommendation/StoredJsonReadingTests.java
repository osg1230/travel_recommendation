package com.travel.travel_recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** 배포 전 형식으로 저장된 작업 JSON을 앱의 공용 매퍼가 읽을 수 있는지 확인한다. */
@SpringBootTest
class StoredJsonReadingTests {
    private record Sample(String name,boolean added,int count) {}
    @Autowired ObjectMapper mapper;

    // record에 새로 더한 true/false·숫자 항목이 옛 JSON에 없거나 null이어도 기본값으로 읽는다.
    @Test void readsOldJsonWithoutNewPrimitiveFields() {
        assertThat(mapper.readValue("{\"name\":\"옛 초안\"}",Sample.class)).isEqualTo(new Sample("옛 초안",false,0));
        assertThat(mapper.readValue("{\"name\":\"옛 초안\",\"added\":null,\"count\":null}",Sample.class)).isEqualTo(new Sample("옛 초안",false,0));
    }
    // 없어진 항목이 옛 JSON에 남아 있어도 읽는다.
    @Test void ignoresFieldsThatNoLongerExist() {
        assertThat(mapper.readValue("{\"name\":\"옛 초안\",\"removed\":true}",Sample.class).name()).isEqualTo("옛 초안");
    }
}
