package com.eventchat.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventchat.it.support.IntegrationTest;
import com.eventchat.testsupport.Api.Response;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

@DisplayName("세션 목록")
class SessionListIT extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    private String t;
    private String car;
    private String tag;
    private List<String> ids;
    private String carId;

    /**
     * 테스트마다 고유 꼬리표를 붙인 세션 5개를 만든다.
     * 통신 4개(0: IN_PROGRESS, 1: SUSPENDED, 2·3: WAITING), 중고차 1개(WAITING).
     */
    @BeforeEach
    void sessions() {
        t = api.token("telecom.user");
        car = api.token("car-sales.customer");
        tag = Long.toString(System.nanoTime(), 36);
        ids = new ArrayList<>();
        for (String title : List.of("요금제 변경 ", "Roaming ABC ", "100% 환불 ", "단말 할부 ")) {
            ids.add(api.post("/sessions", Map.of("title", title + tag), t).text("id"));
        }
        carId = api.post("/sessions", Map.of("title", "중고차 시세 " + tag), car).text("id");
        api.post("/sessions/" + ids.get(0) + "/join", null, car);
        api.post("/sessions/" + ids.get(1) + "/leave", null, t);
    }

    /** 커서를 따라 끝까지 읽는다. 마지막 쪽에서는 nextCursor가 없어야 한다. */
    private List<JsonNode> all(String token, Map<String, ?> params, int limit) {
        List<JsonNode> items = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> query = new HashMap<>(params);
            query.put("limit", limit);
            if (cursor != null) {
                query.put("cursor", cursor);
            }
            Response page = api.get("/sessions", token, query);
            assertThat(page.status()).as(page.toString()).isEqualTo(200);
            page.body().get("items").forEach(items::add);
            cursor = page.body().get("hasMore").asBoolean() ? page.text("nextCursor") : null;
            if (cursor == null) {
                assertThat(page.body().get("nextCursor").isNull()).isTrue();
            }
        } while (cursor != null);
        return items;
    }

    private List<String> ids(String token, Map<String, ?> params) {
        return all(token, params, 100).stream().map(i -> i.get("id").asString()).toList();
    }

    @Test
    @DisplayName("분류코드 하나 또는 여러 개로 거른다")
    void category() {
        assertThat(ids(t, Map.of("category", "TELECOM", "title", tag))).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(ids(t, Map.of("category", List.of("TELECOM", "CAR_SALES"), "title", tag)))
                .containsExactlyInAnyOrder(ids.get(0), ids.get(1), ids.get(2), ids.get(3), carId);
    }

    @Test
    @DisplayName("제목은 부분 일치·대소문자 무시이고 %와 _는 글자 그대로 찾는다")
    void title() {
        assertThat(ids(t, Map.of("title", "abc " + tag))).containsExactly(ids.get(1));
        assertThat(ids(t, Map.of("title", "0% 환불 " + tag))).containsExactly(ids.get(2));
        Response page = api.get("/sessions", t, Map.of("title", "%_"));
        assertThat(page.status()).isEqualTo(200);
        page.body().get("items").forEach(i -> assertThat(i.get("title").asString()).contains("%_"));
    }

    @Test
    @DisplayName("상태 하나 또는 여러 개로 거른다")
    void status() {
        assertThat(ids(t, Map.of("title", tag, "status", "WAITING")))
                .containsExactlyInAnyOrder(ids.get(2), ids.get(3), carId);
        assertThat(ids(t, Map.of("title", tag, "status", List.of("IN_PROGRESS", "SUSPENDED"))))
                .containsExactlyInAnyOrder(ids.get(0), ids.get(1));
    }

    @Test
    @DisplayName("participant=me는 내가 참여 중인 세션만, 퇴장한 세션은 빠진다")
    void participantMe() {
        assertThat(ids(car, Map.of("title", tag, "participant", "me"))).containsExactlyInAnyOrder(ids.get(0), carId);
        assertThat(ids(t, Map.of("title", tag, "participant", "me")))
                .containsExactlyInAnyOrder(ids.get(0), ids.get(2), ids.get(3));
    }

    @Test
    @DisplayName("요약에 참여자 이름과 상태가 함께 나온다")
    void participantsInSummary() {
        JsonNode participants = all(t, Map.of("title", "요금제 변경 " + tag), 100).getFirst().get("participants");
        assertThat(participants).hasSize(2);
        assertThat(participants.get(0).get("userId").asString()).isEqualTo(api.userId("telecom.user").toString());
        assertThat(participants.get(1).get("displayName").asString()).isNotBlank();
        assertThat(participants.get(0).propertyNames())
                .containsExactlyInAnyOrder("userId", "displayName", "role", "category", "state");
    }

    @Test
    @DisplayName("생성일 기간은 KST 날짜 기준이고 양 끝을 포함한다")
    void createdRange() {
        assertThat(ids(t, Map.of("title", tag, "createdFrom", TODAY.toString(), "createdTo", TODAY.toString())))
                .hasSize(5);
        assertThat(ids(t, Map.of("title", tag, "createdTo", TODAY.minusDays(1).toString()))).isEmpty();
        assertThat(ids(t, Map.of("title", tag, "createdFrom", TODAY.plusDays(1).toString()))).isEmpty();
    }

    @Test
    @DisplayName("작은 쪽으로 끝까지 읽은 결과는 한 번에 읽은 결과와 같고, 최신순(createdAt, id 내림차순)이다")
    void keysetPaging() {
        List<JsonNode> big = all(t, Map.of(), 100);
        List<JsonNode> small = all(t, Map.of(), 3);
        assertThat(small).extracting(i -> i.get("id").asString())
                .containsExactlyElementsOf(big.stream().map(i -> i.get("id").asString()).toList());
        assertThat(small.size()).isGreaterThan(3);
        for (int i = 1; i < big.size(); i++) {
            String prev = big.get(i - 1).get("createdAt").asString() + big.get(i - 1).get("id").asString();
            String next = big.get(i).get("createdAt").asString() + big.get(i).get("id").asString();
            assertThat(prev).isGreaterThanOrEqualTo(next);
        }
    }

    @Test
    @DisplayName("다른 조건으로 만든 커서나 깨진 커서는 400 CURSOR_INVALID")
    void cursorErrors() {
        String cursor = api.get("/sessions", t, Map.of("limit", 1, "status", "WAITING")).text("nextCursor");
        Response mismatch = api.get("/sessions", t, Map.of("limit", 1, "status", "COMPLETED", "cursor", cursor));
        assertThat(mismatch.status()).isEqualTo(400);
        assertThat(mismatch.code()).isEqualTo("CURSOR_INVALID");
        assertThat(api.get("/sessions", t, Map.of("cursor", "garbage!!")).code()).isEqualTo("CURSOR_INVALID");
    }

    static List<Arguments> invalidQueries() {
        return List.of(
                Arguments.of("제목 1자", Map.of("title", "a")),
                Arguments.of("시작일 > 종료일", Map.of("createdFrom", "2026-10-22", "createdTo", "2026-10-21")),
                Arguments.of("날짜 형식", Map.of("createdFrom", "2026/10/21")),
                Arguments.of("없는 상태", Map.of("status", "FOO")),
                Arguments.of("participant", Map.of("participant", "you")),
                Arguments.of("limit 0", Map.of("limit", 0)),
                Arguments.of("limit 101", Map.of("limit", 101)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidQueries")
    @DisplayName("잘못된 조건은 400 VALIDATION_FAILED")
    void validation(String name, Map<String, Object> params) {
        Response response = api.get("/sessions", t, params);
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("관리자는 전체 목록을 본다")
    void admin() {
        Response page = api.get("/sessions", api.token("admin"), Map.of("limit", 1));
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body().get("items")).hasSize(1);
        assertThat(page.body().get("hasMore").asBoolean()).isTrue();
    }
}
