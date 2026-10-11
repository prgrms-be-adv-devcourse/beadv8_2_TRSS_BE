package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.dto.AdminCategoryTreeDto;
import com.backend.boundedContext.product.app.dto.CategoryDto;
import com.backend.boundedContext.product.in.dto.CategoryCreateRequest;
import com.backend.boundedContext.product.in.dto.CategoryCreateResponse;
import com.backend.boundedContext.product.in.dto.CategoryModifyRequest;
import com.backend.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "관리자 카테고리", description = "카테고리 관리 (ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/categories")
@RequiredArgsConstructor
public class ApiV1AdminCategoryController {
    private final ProductFacade productFacade;

    @Operation(summary = "관리자 카테고리 트리 조회", description = "사용 중지된 카테고리까지 포함해 반환합니다.")
    @GetMapping
    public RsData<List<AdminCategoryTreeDto>> getCategoryTree() {
        return RsData.of("200-1", "카테고리 목록을 조회했습니다.", productFacade.getCategoryTreeForAdmin());
    }

    @Operation(summary = "소분류 추가", description = "대분류 아래에 소분류를 추가합니다.")
    @PostMapping
    public RsData<CategoryCreateResponse> createCategory(@Valid @RequestBody CategoryCreateRequest request) {
        Long categoryId = productFacade.createCategory(request.name(), request.parentId(), request.sortOrder());
        return RsData.of("200-1", "카테고리를 추가했습니다.", new CategoryCreateResponse(categoryId));
    }

    @Operation(summary = "소분류 수정", description = "이름·정렬 순서·사용 여부 중 보낸 값만 바꿉니다. 대분류는 수정할 수 없습니다.")
    @PatchMapping("/{categoryId}")
    public RsData<CategoryDto> modifyCategory(@PathVariable Long categoryId,
                                              @Valid @RequestBody CategoryModifyRequest request) {
        CategoryDto category = productFacade.modifyCategory(
                categoryId, request.name(), request.sortOrder(), request.active());
        return RsData.of("200-1", "카테고리를 수정했습니다.", category);
    }
}
