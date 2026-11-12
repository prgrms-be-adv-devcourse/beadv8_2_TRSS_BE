package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.dto.OptionStockDto;
import com.backend.boundedContext.product.app.dto.ProductImageFile;
import com.backend.boundedContext.product.app.dto.ProductStopRequestDto;
import com.backend.boundedContext.product.app.dto.StockHistoryDto;
import com.backend.boundedContext.product.app.dto.SellerProductFormDto;
import com.backend.boundedContext.product.app.dto.SellerProductCountsDto;
import com.backend.boundedContext.product.app.dto.SellerProductSummaryDto;
import com.backend.boundedContext.product.domain.ProductDisplayStatus;
import com.backend.boundedContext.product.in.dto.ProductCreateRequest;
import com.backend.boundedContext.product.in.dto.ProductCreateResponse;
import com.backend.boundedContext.product.in.dto.OptionCreateResponse;
import com.backend.boundedContext.product.in.dto.OptionModifyRequest;
import com.backend.boundedContext.product.in.dto.ProductModifyRequest;
import com.backend.boundedContext.product.in.dto.ProductOptionRequest;
import com.backend.boundedContext.product.in.dto.ProductStopRequestCreateRequest;
import com.backend.boundedContext.product.in.dto.StockAdjustRequest;
import com.backend.boundedContext.product.in.dto.StockAdjustResponse;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/**
 * 등록·수정은 multipart로 받는다. 상품 정보는 request 파트(application/json)이고, 이미지는 thumbnail·detailImages 파일 파트다.
 * request 파트에 Content-Type이 없으면 JSON으로 읽지 못하므로, Swagger에서도 application/json으로 보내도록 지정한다.
 * 빈 파일 파트는 보내지 않은 것으로 본다. 이미지 형식·개수 검사와 대표 이미지 필수 검사는 app 계층에서 한다.
 */
@Tag(name = "판매자 상품", description = "판매자 상품 관리 (SELLER)")
@RestController
@RequestMapping("/api/v1/seller/products")
@RequiredArgsConstructor
public class ApiV1SellerProductController {
    private final ProductFacade productFacade;

    @Operation(
            summary = "상품 등록",
            description = "승인된 판매자만 등록할 수 있습니다. 사용 중인 소분류만 고를 수 있습니다. "
                    + "재고는 옵션 조합마다 받습니다. optionNames(최대 3개, 예: [\"색상\", \"사이즈\"])와 "
                    + "options(조합마다 values·additionalPrice·stock, 1~100개)를 보냅니다. "
                    + "values는 optionNames 순서대로 하나씩(각 14자 이하)이고, 단가는 가격 + 추가금입니다. "
                    + "옵션이 없는 상품은 optionNames를 비우고 options에 values가 빈 조합 1개를 보냅니다. "
                    + "대표 이미지(thumbnail)는 필수, 상세 이미지(detailImages)는 선택(최대 10개)입니다. "
                    + "이미지는 jpg·png·webp, 장당 5MB까지입니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                    encoding = @Encoding(name = "request", contentType = MediaType.APPLICATION_JSON_VALUE))))
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RsData<ProductCreateResponse> createProduct(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestPart("request") ProductCreateRequest request,
            @RequestPart(value = "thumbnail", required = false) MultipartFile thumbnail,
            @RequestPart(value = "detailImages", required = false) List<MultipartFile> detailImages
    ) {
        Long productId = productFacade.createProduct(authMember.memberId(), request.categoryId(),
                request.name(), request.description(), request.price(),
                request.optionNamesOrEmpty(), request.toOptionSpecs(),
                toImageFile(thumbnail), toImageFiles(detailImages));
        return RsData.of("200-1", "상품을 등록했습니다.", new ProductCreateResponse(productId));
    }

    @Operation(
            summary = "본인 상품 목록",
            description = "삭제된 상품을 뺀 본인 상품을 최신 등록순으로 줍니다. status가 없으면 전체입니다. "
                    + "탭별 개수는 /counts로 따로 조회합니다.")
    @GetMapping
    public RsData<PageResponse<SellerProductSummaryDto>> getSellerProducts(
            @AuthenticationPrincipal AuthMember authMember,
            @Parameter(description = "ON_SALE(재고 있음) · SOLD_OUT(재고 0) · STOPPED(판매 중지)")
            @RequestParam(required = false) ProductDisplayStatus status,
            @Parameter(description = "상품명 일부 (최대 100자)")
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "상품 목록을 조회했습니다.",
                productFacade.getSellerProducts(authMember.memberId(), status, keyword, pageable));
    }

    @Operation(
            summary = "본인 상품 탭별 개수",
            description = "전체·판매중·품절·판매 중지 개수를 줍니다. 삭제된 상품은 세지 않고, 목록과 같은 keyword를 적용합니다.")
    @GetMapping("/counts")
    public RsData<SellerProductCountsDto> getSellerProductCounts(
            @AuthenticationPrincipal AuthMember authMember,
            @Parameter(description = "상품명 일부 (최대 100자)")
            @RequestParam(required = false) @Size(max = 100) String keyword
    ) {
        return RsData.of("200-1", "상품 개수를 조회했습니다.",
                productFacade.getSellerProductCounts(authMember.memberId(), keyword));
    }

    @Operation(summary = "상품 수정 폼 조회", description = "수정 폼을 채울 본인 상품 정보를 줍니다. 다른 판매자의 상품·삭제된 상품은 404입니다.")
    @GetMapping("/{productId}")
    public RsData<SellerProductFormDto> getSellerProductForm(@AuthenticationPrincipal AuthMember authMember,
                                                             @PathVariable Long productId) {
        return RsData.of("200-1", "상품을 조회했습니다.", productFacade.getSellerProductForm(authMember.memberId(), productId));
    }

    @Operation(
            summary = "상품 수정",
            description = "보낸 항목만 수정하며 재고는 바꿀 수 없습니다. 판매 중지·삭제된 상품은 수정할 수 없습니다. "
                    + "thumbnail을 보내면 대표 이미지를 교체합니다. detailImageIds를 보내면 그 상세 이미지만 그 순서대로 남기고 "
                    + "나머지는 지우며(보내지 않으면 그대로), detailImages는 남긴 이미지 뒤에 추가합니다. 상세 이미지는 합쳐서 최대 10개입니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                    encoding = @Encoding(name = "request", contentType = MediaType.APPLICATION_JSON_VALUE))))
    @PatchMapping(value = "/{productId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RsData<Void> modifyProduct(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long productId,
            @Valid @RequestPart("request") ProductModifyRequest request,
            @RequestPart(value = "thumbnail", required = false) MultipartFile thumbnail,
            @RequestPart(value = "detailImages", required = false) List<MultipartFile> detailImages
    ) {
        productFacade.modifyProduct(authMember.memberId(), productId, request.name(), request.price(),
                request.categoryId(), request.description(), request.detailImageIds(),
                toImageFile(thumbnail), toImageFiles(detailImages));
        return RsData.of("200-1", "상품을 수정했습니다.");
    }

    @Operation(
            summary = "상품 삭제",
            description = "본인 상품을 지웁니다(소프트 삭제). 판매 중(품절 포함)인 상품만 지울 수 있고, 판매 중지 상품은 409입니다. "
                    + "결제 대기 중인 주문은 취소되고, 심사 중인 판매 중지 요청은 닫힙니다.")
    @DeleteMapping("/{productId}")
    public RsData<Void> deleteProduct(@AuthenticationPrincipal AuthMember authMember, @PathVariable Long productId) {
        productFacade.deleteProduct(authMember.memberId(), productId);
        return RsData.of("200-1", "상품을 삭제했습니다.");
    }

    @Operation(
            summary = "판매 중지 요청",
            description = "관리자에게 판매 중지를 요청합니다(사유 필수). 승인될 때까지 판매는 계속됩니다. "
                    + "판매 중지 상품은 409, 심사 중인 요청이 이미 있으면 409-STOP_REQUEST_PENDING입니다.")
    @PostMapping("/{productId}/stop-requests")
    public RsData<ProductStopRequestDto> requestStop(@AuthenticationPrincipal AuthMember authMember,
                                                     @PathVariable Long productId,
                                                     @Valid @RequestBody ProductStopRequestCreateRequest request) {
        return RsData.of("200-1", "판매 중지를 요청했습니다.",
                productFacade.requestStop(authMember.memberId(), productId, request.reason()));
    }

    @Operation(
            summary = "옵션 추가",
            description = "본인 상품에 옵션 조합을 하나 더합니다. values는 상품의 옵션 이름 순서대로 하나씩 보냅니다. "
                    + "판매 중지 상품은 409, 같은 조합이 있으면 409-OPTION_DUPLICATED, 100개를 넘으면 400-OPTION_LIMIT_EXCEEDED입니다.")
    @PostMapping("/{productId}/options")
    public RsData<OptionCreateResponse> addOption(@AuthenticationPrincipal AuthMember authMember,
                                                  @PathVariable Long productId,
                                                  @Valid @RequestBody ProductOptionRequest request) {
        Long optionId = productFacade.addOption(authMember.memberId(), productId, request.toSpec());
        return RsData.of("200-1", "옵션을 추가했습니다.", new OptionCreateResponse(optionId));
    }

    @Operation(
            summary = "옵션 수정",
            description = "옵션의 추가금을 바꿉니다. 옵션 값은 바꿀 수 없습니다(다른 조합은 삭제 후 추가). "
                    + "판매 중지·삭제된 상품과 삭제된 옵션은 409입니다.")
    @PatchMapping("/{productId}/options/{optionId}")
    public RsData<Void> modifyOption(@AuthenticationPrincipal AuthMember authMember,
                                     @PathVariable Long productId,
                                     @PathVariable Long optionId,
                                     @Valid @RequestBody OptionModifyRequest request) {
        productFacade.modifyOption(authMember.memberId(), productId, optionId, request.additionalPrice());
        return RsData.of("200-1", "옵션을 수정했습니다.");
    }

    @Operation(
            summary = "옵션 삭제",
            description = "옵션 조합을 지웁니다(소프트 삭제). 장바구니에 담긴 그 옵션은 판매 불가(OPTION_DELETED)가 됩니다. "
                    + "판매 중지 상품과 이미 삭제된 옵션은 409, 마지막 남은 옵션은 409-LAST_OPTION_NOT_DELETABLE입니다.")
    @DeleteMapping("/{productId}/options/{optionId}")
    public RsData<Void> deleteOption(@AuthenticationPrincipal AuthMember authMember,
                                     @PathVariable Long productId,
                                     @PathVariable Long optionId) {
        productFacade.deleteOption(authMember.memberId(), productId, optionId);
        return RsData.of("200-1", "옵션을 삭제했습니다.");
    }

    @Operation(
            summary = "옵션 재고 이력",
            description = "본인 상품 옵션의 재고 이력을 최신순으로 줍니다. 판매 중지 상품도 볼 수 있습니다. "
                    + "changeType: INIT(등록) · ADJUST(조정) · ORDER_DEDUCT(주문) · ORDER_RESTORE(주문 취소) · RETURN_RESTORE(반품)")
    @GetMapping("/{productId}/options/{optionId}/stock-history")
    public RsData<PageResponse<StockHistoryDto>> getStockHistories(@AuthenticationPrincipal AuthMember authMember,
                                                                   @PathVariable Long productId,
                                                                   @PathVariable Long optionId,
                                                                   @ParameterObject Pageable pageable) {
        return RsData.of("200-1", "재고 이력을 조회했습니다.",
                productFacade.getStockHistories(authMember.memberId(), productId, optionId, pageable));
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

    /** 빈 파트는 보내지 않은 것(null)으로 본다 */
    private static ProductImageFile toImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;
        try {
            return new ProductImageFile(file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("업로드한 이미지를 읽지 못했습니다.", e);
        }
    }

    private static List<ProductImageFile> toImageFiles(List<MultipartFile> files) {
        if (files == null) return List.of();
        return files.stream().map(ApiV1SellerProductController::toImageFile).filter(Objects::nonNull).toList();
    }
}
