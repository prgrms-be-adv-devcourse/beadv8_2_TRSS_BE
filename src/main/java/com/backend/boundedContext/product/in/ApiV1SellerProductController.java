package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.dto.OptionStockDto;
import com.backend.boundedContext.product.in.dto.ProductCreateRequest;
import com.backend.boundedContext.product.in.dto.ProductCreateResponse;
import com.backend.boundedContext.product.in.dto.StockAdjustRequest;
import com.backend.boundedContext.product.in.dto.StockAdjustResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "판매자 상품", description = "판매자 상품 관리 (SELLER)")
@RestController
@RequestMapping("/api/v1/seller/products")
@RequiredArgsConstructor
public class ApiV1SellerProductController {
    private final ProductFacade productFacade;

    /**
     * multipart로 받는다. 상품 정보는 request 파트(application/json)이고, 이미지 파트는 이미지 이슈에서 추가한다.
     * request 파트에 Content-Type이 없으면 JSON으로 읽지 못하므로, Swagger에서도 application/json으로 보내도록 지정한다.
     */
    @Operation(
            summary = "상품 등록",
            description = "승인된 판매자만 등록할 수 있습니다. 사용 중인 소분류만 고를 수 있고, 초기 재고는 기본 옵션의 재고가 됩니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                    encoding = @Encoding(name = "request", contentType = MediaType.APPLICATION_JSON_VALUE))))
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RsData<ProductCreateResponse> createProduct(@AuthenticationPrincipal AuthMember authMember,
                                                       @Valid @RequestPart("request") ProductCreateRequest request) {
        Long productId = productFacade.createProduct(authMember.memberId(), request.categoryId(),
                request.name(), request.description(), request.price(), request.stock());
        return RsData.of("200-1", "상품을 등록했습니다.", new ProductCreateResponse(productId));
    }

    @Operation(
            summary = "옵션 재고 조정",
            description = "자기 상품 옵션의 재고를 증감량만큼 조정합니다. 늘리면 양수, 줄이면 음수이며 결과가 0 미만이면 안 됩니다. "
                    + "판매 중지·삭제된 상품과 삭제된 옵션은 조정할 수 없습니다.")
    @PostMapping("/{productId}/options/{optionId}/stock-adjustments")
    public RsData<StockAdjustResponse> adjustStock(@AuthenticationPrincipal AuthMember authMember,
                                                   @PathVariable Long productId,
                                                   @PathVariable Long optionId,
                                                   @Valid @RequestBody StockAdjustRequest request) {
        OptionStockDto result = productFacade.adjustStock(authMember.memberId(), productId, optionId, request.changeQty());
        return RsData.of("200-1", "재고를 조정했습니다.", new StockAdjustResponse(result.optionId(), result.stock()));
    }
}
