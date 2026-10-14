package com.backend.boundedContext.member.in;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.member.app.MemberSellerFacade;
import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.app.dto.AdminSellerDto;
import com.backend.boundedContext.member.in.dto.SellerReasonRequest;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.shared.member.dto.SellerStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "관리자 판매자", description = "판매자 목록·상세 조회, 입점 심사, 철회 심사, 제재")
@RestController
@RequestMapping("/api/v1/admin/sellers")
@RequiredArgsConstructor
public class ApiV1AdminSellerController {

    private final MemberSellerFacade memberSellerFacade;

    @Operation(summary = "판매자 목록 조회", description = "심사 대기(PENDING)를 먼저, 그다음 최근 신청순으로 고정(sort 값은 무시). page(0부터), size(기본 20, 최대 100)")
    @GetMapping
    public RsData<PageResponse<AdminSellerDto>> getSellers(
            @RequestParam(required = false) SellerStatus status,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "판매자 목록을 조회했습니다.", memberSellerFacade.getSellersForAdmin(status, pageable));
    }

    @Operation(summary = "판매자 상세 조회", description = "판매자 정보와 상태 이력(오래된 순)을 반환합니다. 계좌번호는 끝 4자리만 보여 줍니다.")
    @GetMapping("/{sellerId}")
    public RsData<AdminSellerDetailDto> getSellerDetail(
            @PathVariable Long sellerId
    ) {
        return RsData.of("200-1", "판매자 상세를 조회했습니다.", memberSellerFacade.getSellerDetail(sellerId));
    }

    @Operation(summary = "입점 승인", description = "심사 대기(PENDING)만 승인할 수 있고, 회원에게 판매자 권한을 줍니다. 바뀐 판매자 상세를 반환합니다.")
    @PostMapping("/{sellerId}/approve")
    public RsData<AdminSellerDetailDto> approve(
            @PathVariable Long sellerId
    ) {
        return RsData.of("200-1", "판매자 입점을 승인했습니다.", memberSellerFacade.approveSeller(sellerId));
    }

    @Operation(summary = "입점 반려", description = "심사 대기(PENDING)만 반려할 수 있고, 사유는 필수입니다. 신청 내용은 이력에 남고 사업자번호는 비워집니다.")
    @PostMapping("/{sellerId}/reject")
    public RsData<AdminSellerDetailDto> reject(
            @PathVariable Long sellerId,
            @Valid @RequestBody SellerReasonRequest request
    ) {
        return RsData.of("200-1", "판매자 입점을 반려했습니다.", memberSellerFacade.rejectSeller(sellerId, request.reason()));
    }

    @Operation(summary = "철회 승인", description = "철회 심사 중(WITHDRAW_REQUESTED)만 승인할 수 있습니다. 진행 중인 판매 주문이나 미정산 금액이 있으면 409입니다. 신청 내용은 이력에 남고 사업자번호는 비워집니다.")
    @PostMapping("/{sellerId}/withdrawal/approve")
    public RsData<AdminSellerDetailDto> approveWithdrawal(
            @PathVariable Long sellerId
    ) {
        return RsData.of("200-1", "판매자 철회를 승인했습니다.", memberSellerFacade.approveWithdrawal(sellerId));
    }

    @Operation(summary = "철회 거절", description = "철회 심사 중(WITHDRAW_REQUESTED)만 거절할 수 있고, 사유는 필수입니다. 판매자는 다시 판매 중(APPROVED)이 됩니다.")
    @PostMapping("/{sellerId}/withdrawal/reject")
    public RsData<AdminSellerDetailDto> rejectWithdrawal(
            @PathVariable Long sellerId,
            @Valid @RequestBody SellerReasonRequest request
    ) {
        return RsData.of("200-1", "판매자 철회를 거절했습니다.", memberSellerFacade.rejectWithdrawal(sellerId, request.reason()));
    }
}
