package com.backend.boundedContext.member.in;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.member.app.MemberSellerFacade;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;

/**
 * 판매자 내부 API의 요청·응답 모양을 확인함
 * /internal 접근 제한(보안 설정)은 이 테스트 범위가 아니라서 보안 필터는 끔
 */
@WebMvcTest(InternalSellerController.class)
@AutoConfigureMockMvc(addFilters = false)
class InternalSellerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberSellerFacade memberSellerFacade;

    private static SellerDto seller(Long sellerId, Long memberId, SellerStatus status) {
        return new SellerDto(sellerId, memberId, "상점" + sellerId, status);
    }

    @Test
    @DisplayName("판매자 조회: MemberApi.getSeller 결과를 RsData로 돌려줌")
    void getSeller() throws Exception {
        given(memberSellerFacade.getSeller(5L)).willReturn(seller(5L, 3L, SellerStatus.APPROVED));

        mockMvc.perform(get("/internal/v1/sellers/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.sellerId").value(5))
                .andExpect(jsonPath("$.data.memberId").value(3))
                .andExpect(jsonPath("$.data.businessName").value("상점5"))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    @DisplayName("판매자 조회: 없으면 404-SELLER_NOT_FOUND")
    void getSeller_notFound() throws Exception {
        given(memberSellerFacade.getSeller(99L)).willThrow(new DomainException(MemberErrorCode.SELLER_NOT_FOUND));

        mockMvc.perform(get("/internal/v1/sellers/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultCode").value("404-SELLER_NOT_FOUND"));
    }

    @Test
    @DisplayName("여러 판매자 조회: ids를 쉼표로 받아 있는 판매자만 돌려줌")
    void getSellers() throws Exception {
        given(memberSellerFacade.getSellers(List.of(5L, 6L, 99L)))
                .willReturn(List.of(seller(5L, 3L, SellerStatus.APPROVED), seller(6L, 4L, SellerStatus.SANCTIONED)));

        mockMvc.perform(get("/internal/v1/sellers").param("ids", "5,6,99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].sellerId").value(5))
                .andExpect(jsonPath("$.data[1].status").value("SANCTIONED"));
    }

    @Test
    @DisplayName("여러 판매자 조회: ids가 없으면 빈 목록")
    void getSellers_empty() throws Exception {
        given(memberSellerFacade.getSellers(List.of())).willReturn(List.of());

        mockMvc.perform(get("/internal/v1/sellers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("회원의 판매자 정보 조회: 상태와 관계없이 돌려줌")
    void findSellerByMemberId() throws Exception {
        given(memberSellerFacade.findSellerByMemberId(3L)).willReturn(Optional.of(seller(5L, 3L, SellerStatus.PENDING)));

        mockMvc.perform(get("/internal/v1/sellers/by-member/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sellerId").value(5))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("회원의 판매자 정보 조회: 신청한 적이 없으면 200, data null")
    void findSellerByMemberId_none() throws Exception {
        given(memberSellerFacade.findSellerByMemberId(3L)).willReturn(Optional.empty());

        mockMvc.perform(get("/internal/v1/sellers/by-member/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("승인된 판매자 조회: APPROVED면 돌려줌")
    void getApprovedSellerByMemberId() throws Exception {
        given(memberSellerFacade.getApprovedSellerByMemberId(3L)).willReturn(seller(5L, 3L, SellerStatus.APPROVED));

        mockMvc.perform(get("/internal/v1/sellers/by-member/3/approved"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sellerId").value(5));
    }

    @Test
    @DisplayName("승인된 판매자 조회: 판매 기능을 쓸 수 없는 상태면 403-SELLER_NOT_APPROVED")
    void getApprovedSellerByMemberId_notApproved() throws Exception {
        given(memberSellerFacade.getApprovedSellerByMemberId(3L))
                .willThrow(new DomainException(MemberErrorCode.SELLER_NOT_APPROVED));

        mockMvc.perform(get("/internal/v1/sellers/by-member/3/approved"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value("403-SELLER_NOT_APPROVED"));
    }
}
