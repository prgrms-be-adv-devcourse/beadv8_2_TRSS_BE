package com.backend.ai.client;

import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MemberInfluencerApiClientTest {

    private static final String BASE_URL = "http://member:8081";

    private MockRestServiceServer server;
    private MemberInfluencerApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MemberInfluencerApiClient(builder, BASE_URL, "test-internal-api-key");
    }

    @Test
    @DisplayName("회원의 승인 인플루언서: 승인이면 담고, 403(승인 아님)·404(신청 없음)면 empty. 내부 키 헤더를 붙인다")
    void findApprovedByMemberId() {
        server.expect(requestTo(BASE_URL + "/internal/v1/influencers/by-member/7/approved"))
                .andExpect(header("X-Internal-Key", "test-internal-api-key"))
                .andRespond(withSuccess("""
                        {"resultCode":"200-1","msg":"ok","data":{"influencerId":5,"memberId":7,"activityName":"민지","status":"APPROVED"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/internal/v1/influencers/by-member/8/approved"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"resultCode\":\"403-INFLUENCER_NOT_APPROVED\",\"msg\":\"승인된 인플루언서가 아닙니다.\",\"data\":null}"));
        server.expect(requestTo(BASE_URL + "/internal/v1/influencers/by-member/9/approved"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.findApprovedByMemberId(7L)).hasValueSatisfying(i -> assertThat(i.influencerId()).isEqualTo(5L));
        assertThat(client.findApprovedByMemberId(8L)).isEmpty();
        assertThat(client.findApprovedByMemberId(9L)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("여러 명 조회: ids로 묻고, 회원 서비스가 실패하면 502 EXTERNAL_SERVICE_ERROR")
    void getInfluencers() {
        server.expect(requestTo(BASE_URL + "/internal/v1/influencers?ids=5&ids=6"))
                .andRespond(withSuccess("""
                        {"resultCode":"200-1","msg":"ok","data":[{"influencerId":5,"memberId":7,"activityName":"민지","status":"APPROVED"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/internal/v1/influencers?ids=5"))
                .andRespond(withServerError());

        assertThat(client.getInfluencers(java.util.List.of(5L, 6L))).hasSize(1);
        assertThatThrownBy(() -> client.getInfluencers(java.util.List.of(5L)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo("502-EXTERNAL_SERVICE_ERROR");
    }
}
