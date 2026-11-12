package com.backend.boundedContext.member.in;

import com.backend.boundedContext.member.app.MemberInfluencerFacade;
import com.backend.boundedContext.member.domain.InfluencerStatus;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.in.dto.InternalInfluencerResponse;
import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인플루언서 내부 API의 요청·응답 모양을 확인한다
 * /internal 접근 제한(보안 설정)은 이 테스트 범위가 아니라서 보안 필터는 끈다
 */
@WebMvcTest(InternalInfluencerController.class)
@AutoConfigureMockMvc(addFilters = false)
class InternalInfluencerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberInfluencerFacade memberInfluencerFacade;

    @Test
    @DisplayName("단건 조회: 승인 전이어도 상태와 함께 돌려준다")
    void getInfluencer() throws Exception {
        given(memberInfluencerFacade.getInternalInfluencer(7L)).willReturn(new InternalInfluencerResponse(7L, 3L, "민지", InfluencerStatus.PENDING));

        mockMvc.perform(get("/internal/v1/influencers/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(3))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("단건 조회: 없으면 404-INFLUENCER_NOT_FOUND")
    void getInfluencer_notFound() throws Exception {
        given(memberInfluencerFacade.getInternalInfluencer(99L)).willThrow(new DomainException(MemberErrorCode.INFLUENCER_NOT_FOUND));

        mockMvc.perform(get("/internal/v1/influencers/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultCode").value("404-INFLUENCER_NOT_FOUND"));
    }

    @Test
    @DisplayName("여러 명 조회: ids로 받은 인플루언서 목록")
    void getInfluencers() throws Exception {
        given(memberInfluencerFacade.getInternalInfluencers(List.of(7L, 8L))).willReturn(List.of(
                new InternalInfluencerResponse(7L, 3L, "민지", InfluencerStatus.APPROVED)));

        mockMvc.perform(get("/internal/v1/influencers").param("ids", "7,8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].activityName").value("민지"));
    }

    @Test
    @DisplayName("승인된 인플루언서 조회: 아니면 403-INFLUENCER_NOT_APPROVED")
    void getApproved_notApproved() throws Exception {
        given(memberInfluencerFacade.getApprovedInfluencerByMemberId(3L)).willThrow(new DomainException(MemberErrorCode.INFLUENCER_NOT_APPROVED));

        mockMvc.perform(get("/internal/v1/influencers/by-member/3/approved"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-INFLUENCER_NOT_APPROVED"));
    }
}
