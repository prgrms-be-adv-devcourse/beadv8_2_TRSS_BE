package com.backend.boundedContext.settlement.in.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.boundedContext.settlement.in.dto.SettlementLedgerMismatchConfirmResponse;

/**
 * 관리자 원장 재대조 불일치 확인 API의 요청·응답 모양을 확인함
 * 관리자 권한(보안 설정)은 이 테스트 범위가 아니라서 보안 필터는 끔
 */
@WebMvcTest(ApiV1AdminSettlementController.class)
@AutoConfigureMockMvc(addFilters = false)
class ApiV1AdminSettlementLedgerConfirmControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SettlementFacade settlementFacade;

    @Test
    @DisplayName("불일치 확인: 사유를 넘기고 자동 해제 수를 돌려줌")
    void confirm() throws Exception {
        given(settlementFacade.confirmLedgerMismatch(31L, "결제 쪽 정정 확인"))
                .willReturn(new SettlementLedgerMismatchConfirmResponse(31L, 2));

        mockMvc.perform(post("/api/v1/admin/settlements/31/ledger-mismatch/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"결제 쪽 정정 확인\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data.settlementId").value(31))
                .andExpect(jsonPath("$.data.releasedCount").value(2));
    }

    @Test
    @DisplayName("불일치 확인: 사유가 없거나 공백이면 400 VALIDATION_FAILED이고 확인하지 않음")
    void confirm_noReason() throws Exception {
        mockMvc.perform(post("/api/v1/admin/settlements/31/ledger-mismatch/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-VALIDATION_FAILED"));

        verify(settlementFacade, never()).confirmLedgerMismatch(anyLong(), any());
    }
}
