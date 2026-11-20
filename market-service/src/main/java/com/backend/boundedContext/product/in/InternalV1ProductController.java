package com.backend.boundedContext.product.in;

import java.util.List;
import java.util.Objects;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.rsData.RsData;
import com.backend.shared.product.dto.OptionSnapshotDto;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

/**
 * 다른 서비스가 호출하는 상품 내부 API. 규칙은 ProductApi를 따른다.
 * 서비스끼리만 부르는 API라 Swagger에서 숨기고, X-Internal-Key로 인증한다(InternalApiKeyFilter)
 */
@Hidden
@RestController
@RequestMapping("/internal/v1/products")
@RequiredArgsConstructor
public class InternalV1ProductController {

    // 한 번에 조회할 수 있는 옵션 수(중복 제외). 넘으면 400-VALIDATION_FAILED. 부르는 쪽이 이 단위로 나눠 부른다
    static final int MAX_OPTION_IDS = 200;

    private final ProductFacade productFacade;

    @Operation(summary = "옵션 여러 개의 현재 정보 (삭제된 상품·옵션 포함, 없는 ID는 빠짐)")
    @GetMapping("/options")
    public RsData<List<OptionSnapshotDto>> getOptions(@RequestParam(required = false) List<Long> optionIds) {
        List<Long> ids = optionIds == null ? List.of() : optionIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > MAX_OPTION_IDS) {
            throw new DomainException(GlobalErrorCode.VALIDATION_FAILED);
        }
        List<OptionSnapshotDto> options = ids.isEmpty() ? List.of() : productFacade.getOptions(ids);
        return RsData.of("200-1", "옵션 정보를 조회했습니다.", options);
    }
}
