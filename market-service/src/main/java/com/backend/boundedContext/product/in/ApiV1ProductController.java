package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.dto.PopularKeywordDto;
import com.backend.boundedContext.product.app.dto.ProductDetailDto;
import com.backend.boundedContext.product.app.dto.MemberRecommendationsDto;
import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "상품", description = "상품 검색·인기 검색어·상세 (로그인 불필요)")
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ApiV1ProductController {
    private final ProductFacade productFacade;

    @Operation(
            summary = "상품 검색",
            description = "판매 중이고 사용 중인 카테고리의 상품을 검색합니다. 검색어는 상품명과 판매자 상호명에서 형태소(nori)로 찾고, "
                    + "상품명이 맞는 상품을 상호명만 맞는 상품보다 앞에 둡니다(관련도순). size는 최대 50입니다. "
                    + "검색 서버가 응답하지 않으면 DB 검색으로 대신하며, 이때 상품명으로만 찾고, 관련도순·인기순·찜 많은순·판매량순은 최신순, 가격순은 정가 기준입니다. "
                    + "할인 시작·종료 직후 약 10초 동안은 특가 필터·할인율순·가격순이 이전 상태로 보일 수 있습니다(화면 가격은 정확). "
                    + "검색 색인이 잠깐 늦어 보여 줄 수 없는 상품은 빠지므로 한 페이지가 size보다 적을 수 있습니다.")
    @GetMapping
    public RsData<PageResponse<ProductSummaryDto>> searchProducts(
            @Parameter(description = "상품명 또는 판매자 상호명 (최대 100자)")
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @Parameter(description = "대분류 ID면 그 아래 소분류 전체, 소분류 ID면 그 소분류만")
            @RequestParam(required = false) Long categoryId,
            @Parameter(description = "RELEVANCE(관련도순) · LATEST(최신순) · PRICE_ASC · PRICE_DESC(가격순, 할인 중이면 할인가 기준) · "
                    + "DISCOUNT_RATE(할인율 높은순, 할인 없는 상품은 뒤) · POPULAR(인기순: 찜·별점·최근 30일 판매량) · "
                    + "WISH(찜 많은순) · SALES(최근 30일 판매량순, 결제 기준, 취소·반품 제외). "
                    + "보내지 않으면 검색어가 있을 때 RELEVANCE, 없을 때 LATEST. 검색어 없이 RELEVANCE면 LATEST로 처리")
            @RequestParam(required = false) ProductSort sort,
            @Parameter(description = "true면 품절 상품 제외")
            @RequestParam(defaultValue = "true") boolean excludeSoldOut,
            @Parameter(description = "true면 지금 할인 중인 상품만 (특가)")
            @RequestParam(defaultValue = "false") boolean discounted,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "상품 목록을 조회했습니다.",
                productFacade.searchProducts(keyword, categoryId, sort, excludeSoldOut, discounted, pageable));
    }

    @Operation(
            summary = "인기 검색어",
            description = "최근 24시간 동안 많이 검색한 검색어 상위 10개입니다. 결과가 있었던 첫 페이지 검색만 셉니다. "
                    + "rankChange는 한 시간 전 순위와의 차이이고, 새로 오른 검색어는 null입니다. "
                    + "1분마다 갱신하며, 집계 저장소가 응답하지 않으면 빈 목록입니다.")
    @GetMapping("/popular-keywords")
    public RsData<List<PopularKeywordDto>> getPopularKeywords() {
        return RsData.of("200-1", "인기 검색어를 조회했습니다.", productFacade.getPopularKeywords());
    }

    @Operation(
            summary = "상품 상세",
            description = "판매 중지·삭제된 상품과 사용 중지된 카테고리의 상품은 404입니다. 재고가 5개 이하인 옵션만 남은 수량을 줍니다. "
                    + "로그인 토큰을 보내면 wished에 찜 여부를 채우고, 없으면 false입니다.")
    @GetMapping("/{productId}")
    public RsData<ProductDetailDto> getProductDetail(
            @PathVariable Long productId,
            @AuthenticationPrincipal AuthMember authMember   // 로그인하지 않았으면 null
    ) {
        Long memberId = authMember == null ? null : authMember.memberId();
        return RsData.of("200-1", "상품을 조회했습니다.", productFacade.getProductDetail(productId, memberId));
    }

    @Operation(
            summary = "비슷한 상품",
            description = "상품 상세 하단의 비슷한 상품입니다. 같은 대분류에서 상품명·설명이 비슷하고(형태소), 같은 소분류이고, "
                    + "가격대가 가깝고, 찜·별점·최근 판매량이 높은 상품을 고릅니다. 기준 상품과 품절 상품은 빠집니다. "
                    + "기준 상품이 없거나 판매 중이 아니면 404입니다. size는 1~20이고 기본 10입니다. "
                    + "검색 서버가 응답하지 않으면 같은 대분류의 최근 상품을 같은 소분류·가격대 순으로 줍니다.")
    @GetMapping("/{productId}/similar")
    public RsData<List<ProductSummaryDto>> getSimilarProducts(
            @PathVariable Long productId,
            @Parameter(description = "개수 (1~20, 범위를 넘으면 가까운 값으로 맞춘다)")
            @RequestParam(defaultValue = "10") int size
    ) {
        return RsData.of("200-1", "비슷한 상품을 조회했습니다.", productFacade.getSimilarProducts(productId, size));
    }

    @Operation(
            summary = "회원님을 위한 추천",
            description = "내 구매·높게 평가한 상품(별점 4점 이상)·찜·장바구니와 비슷한 상품을 추천 이유와 함께 줍니다(forYou). "
                    + "sections는 대표 기준 상품(최근 구매·높게 평가한 상품·최근 찜)마다 비슷한 상품입니다. "
                    + "같은 상품은 한 번만 나오고, 이미 산·찜한·담은 상품과 1~2점을 준 상품, 품절 상품은 빠집니다. "
                    + "로그인하지 않았거나 이력이 없으면 personalized=false이고 forYou는 인기순, sections는 빈 목록입니다. "
                    + "찜·구매·리뷰 뒤 몇 초 안에 다시 계산하고, 장바구니·주문 취소는 최대 한 시간 늦게 반영됩니다. size는 1~50, 기본 20입니다.")
    @GetMapping("/recommendations/me")
    public RsData<MemberRecommendationsDto> getMemberRecommendations(
            @AuthenticationPrincipal AuthMember authMember,   // 로그인하지 않았으면 null
            @Parameter(description = "회원님을 위한 추천 개수 (1~50, 범위를 넘으면 가까운 값으로 맞춘다)")
            @RequestParam(defaultValue = "20") int size
    ) {
        Long memberId = authMember == null ? null : authMember.memberId();
        return RsData.of("200-1", "추천 상품을 조회했습니다.", productFacade.getMemberRecommendations(memberId, size));
    }

    @Operation(
            summary = "최근 본 상품 기반 추천",
            description = "최근 본 상품과 비슷한 상품입니다. 프론트가 저장한 최근 본 상품 ID를 최근 순으로 보냅니다(앞의 20개만 씀). "
                    + "없는 상품 ID는 무시하고, 최근 본 상품과 품절 상품은 빠집니다. 기준 상품이 없으면 빈 목록입니다. "
                    + "로그인하지 않아도 됩니다. size는 1~50, 기본 20입니다.")
    @GetMapping("/recommendations/recent")
    public RsData<List<ProductSummaryDto>> getRecentRecommendations(
            @Parameter(description = "최근 본 상품 ID, 최근 순 (예: 12,34,56)")
            @RequestParam(required = false) List<Long> recentViewIds,
            @Parameter(description = "개수 (1~50, 범위를 넘으면 가까운 값으로 맞춘다)")
            @RequestParam(defaultValue = "20") int size
    ) {
        return RsData.of("200-1", "추천 상품을 조회했습니다.", productFacade.getRecentRecommendations(recentViewIds, size));
    }
}
