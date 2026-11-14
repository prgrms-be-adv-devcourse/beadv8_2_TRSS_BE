package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.dto.CategoryTreeDto;
import com.backend.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "카테고리", description = "카테고리 조회")
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class ApiV1CategoryController {
    private final ProductFacade productFacade;

    @Operation(summary = "카테고리 트리 조회", description = "사용 중인 카테고리만 대분류-소분류 트리로 반환합니다. "
            + "productCount는 판매 중인 상품 수(품절 제외)로, 상품 검색 기본값과 같은 기준이며 대분류는 아래 소분류의 합입니다.")
    @GetMapping
    public RsData<List<CategoryTreeDto>> getCategoryTree() {
        return RsData.of("200-1", "카테고리 목록을 조회했습니다.", productFacade.getCategoryTree());
    }
}
