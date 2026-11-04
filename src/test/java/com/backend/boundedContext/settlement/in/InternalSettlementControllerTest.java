package com.backend.boundedContext.settlement.in;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.boundedContext.settlement.app.SettlementFacade;

/**
 * 정산 내부 API의 요청·응답 모양을 확인함
 * /internal 접근 제한(보안 설정)은 이 테스트 범위가 아니라서 보안 필터는 끔
 */
@WebMvcTest(InternalSettlementController.class)
@AutoConfigureMockMvc(addFilters = false)
class InternalSettlementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SettlementFacade settlementFacade;

    @Test
    @DisplayName("미정산 금액 조회: SettlementApi.getUnsettledAmount 결과를 RsData로 돌려줌")
    void getUnsettledAmount() throws Exception {
        given(settlementFacade.getUnsettledAmount(5L)).willReturn(27_000L);

        mockMvc.perform(get("/internal/v1/settlements/sellers/5/unsettled-amount"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data").value(27_000));
    }

    @Test
    @DisplayName("미정산 금액 조회: 정산 후보가 없는 판매자는 200, data 0")
    void getUnsettledAmount_none() throws Exception {
        given(settlementFacade.getUnsettledAmount(9L)).willReturn(0L);

        mockMvc.perform(get("/internal/v1/settlements/sellers/9/unsettled-amount"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.data").value(0));
    }
}
