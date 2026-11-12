package com.backend.market.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backend.global.exception.DomainException;

class MemberInfluencerApiClientTest {

    private static final String BASE_URL = "http://member:8080";
    private static final String INTERNAL_API_KEY = "test-internal-api-key";

    private MockRestServiceServer server;
    private MemberInfluencerApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MemberInfluencerApiClient(builder, BASE_URL, INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("인플루언서 조회: 있으면 담아서, 404면 empty로 돌려준다")
    void findInfluencer() {
        server.expect(requestTo(BASE_URL + "/internal/v1/influencers/5"))
                .andRespond(withSuccess(rsData("""
                        {"influencerId": 5, "memberId": 7, "activityName": "민지", "status": "PENDING"}
                        """), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/internal/v1/influencers/6"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"resultCode": "404-INFLUENCER_NOT_FOUND", "msg": "인플루언서 신청 내역이 없습니다.", "data": null}
                                """));

        assertThat(client.findInfluencer(5L)).hasValueSatisfying(influencer -> {
            assertThat(influencer.activityName()).isEqualTo("민지");
            assertThat(influencer.approved()).isFalse();
        });
        assertThat(client.findInfluencer(6L)).isEmpty();
    }

    @Test
    @DisplayName("승인되지 않은 인플루언서면 403-INFLUENCER_NOT_APPROVED를 그대로 던진다")
    void getApprovedInfluencerByMemberId_notApproved() {
        server.expect(requestTo(BASE_URL + "/internal/v1/influencers/by-member/7/approved"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"resultCode": "403-INFLUENCER_NOT_APPROVED", "msg": "승인된 인플루언서만 이용할 수 있습니다.", "data": null}
                                """));

        assertThatThrownBy(() -> client.getApprovedInfluencerByMemberId(7L))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("403-INFLUENCER_NOT_APPROVED"));
    }

    private static String rsData(String dataJson) {
        return """
                {"resultCode": "200-1", "msg": "성공", "data": %s}
                """.formatted(dataJson);
    }
}
