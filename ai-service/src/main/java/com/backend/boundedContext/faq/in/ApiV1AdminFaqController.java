package com.backend.boundedContext.faq.in;

import com.backend.boundedContext.faq.app.FaqFacade;
import com.backend.boundedContext.faq.app.dto.AdminFaqDto;
import com.backend.boundedContext.faq.domain.FaqCategory;
import com.backend.boundedContext.faq.in.dto.FaqCreateRequest;
import com.backend.boundedContext.faq.in.dto.FaqCreateResponse;
import com.backend.boundedContext.faq.in.dto.FaqModifyRequest;
import com.backend.boundedContext.faq.in.dto.FaqVisibilityRequest;
import com.backend.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "관리자 FAQ", description = "챗봇이 답변 근거로 쓰는 FAQ 관리 (ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/faqs")
@RequiredArgsConstructor
public class ApiV1AdminFaqController {

    private final FaqFacade faqFacade;

    @Operation(summary = "FAQ 목록",
            description = "숨긴 FAQ까지 최근 등록 순으로 모두 줍니다(페이지 없음). category를 보내면 그 카테고리만 줍니다. "
                    + "embedded=false면 아직 챗봇 검색에 반영 전입니다. 보통 수 초 안에 반영되고, OpenAI 장애 중이면 복구될 때까지 false입니다.")
    @GetMapping
    public RsData<List<AdminFaqDto>> getList(
            @RequestParam(required = false) FaqCategory category
    ) {
        return RsData.of("200-1", "FAQ 목록을 조회했습니다.", faqFacade.getAdminList(category));
    }

    @Operation(summary = "FAQ 등록", description = "등록하면 수 초 안에 챗봇 검색에 반영됩니다.")
    @PostMapping
    public RsData<FaqCreateResponse> create(
            @Valid @RequestBody FaqCreateRequest request
    ) {
        Long faqId = faqFacade.create(request.category(), request.question(), request.answer(), request.visible());
        return RsData.of("200-1", "FAQ를 등록했습니다.", new FaqCreateResponse(faqId));
    }

    @Operation(summary = "FAQ 수정",
            description = "카테고리·질문·답변을 모두 바꿉니다. 질문·답변이 바뀌면 다시 임베딩될 때까지 embedded=false입니다. 삭제는 없고, 내리려면 노출 변경을 씁니다.")
    @ApiResponse(responseCode = "404", description = "404-FAQ_NOT_FOUND FAQ가 없음")
    @PutMapping("/{faqId}")
    public RsData<AdminFaqDto> modify(
            @PathVariable Long faqId,
            @Valid @RequestBody FaqModifyRequest request
    ) {
        return RsData.of("200-1", "FAQ를 수정했습니다.", faqFacade.modify(faqId, request.category(), request.question(), request.answer()));
    }

    @Operation(summary = "FAQ 노출 변경", description = "visible=false면 챗봇 검색에서 빠집니다. 다시 켜면 바로 검색됩니다.")
    @ApiResponse(responseCode = "404", description = "404-FAQ_NOT_FOUND FAQ가 없음")
    @PatchMapping("/{faqId}/visibility")
    public RsData<AdminFaqDto> changeVisibility(
            @PathVariable Long faqId,
            @Valid @RequestBody FaqVisibilityRequest request
    ) {
        return RsData.of("200-1", "FAQ 노출 여부를 바꿨습니다.", faqFacade.changeVisibility(faqId, request.visible()));
    }
}
