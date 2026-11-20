package com.backend.boundedContext.faq.in;

import com.backend.boundedContext.faq.app.FaqFacade;
import com.backend.boundedContext.faq.app.dto.FaqSearchDto;
import com.backend.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "FAQ", description = "FAQ 의미 기반 검색")
@RestController
@RequestMapping("/api/v1/ai/faqs")
@RequiredArgsConstructor
public class ApiV1FaqController {

    private final FaqFacade faqFacade;

    @Operation(
            summary = "FAQ 의미 기반 검색",
            description = "질문과 뜻이 가까운 노출 중 FAQ를 유사도 높은 순으로 줍니다. 단어가 겹치지 않아도 찾습니다. 유사도 기준값보다 낮은 FAQ는 빠지고, 관련 FAQ가 없으면 faqs가 빈 목록입니다. 로그인이 필요합니다.")
    @ApiResponse(responseCode = "502", description = "502-EXTERNAL_SERVICE_ERROR 질문 임베딩(OpenAI) 실패")
    @GetMapping("/search")
    public RsData<FaqSearchDto> search(
            @RequestParam @NotBlank @Size(max = 200) String query
    ) {
        return RsData.of("200-1", "FAQ를 검색했습니다.", faqFacade.search(query.strip()));
    }
}
