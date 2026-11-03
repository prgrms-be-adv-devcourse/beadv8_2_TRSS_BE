package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.AdminProductStopRequestDto;
import com.backend.boundedContext.product.domain.ProductStopRequestStatus;
import com.backend.boundedContext.product.out.ProductStopRequestRepository;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductGetStopRequestsUseCase {
    private static final Sort OLDEST = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

    private final ProductStopRequestRepository productStopRequestRepository;

    /** 관리자 판매 중지 요청 목록. 먼저 들어온 요청부터 처리하도록 오래된 순이다. */
    public PageResponse<AdminProductStopRequestDto> getStopRequests(ProductStopRequestStatus status, Pageable pageable) {
        Pageable oldest = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), OLDEST);
        return PageResponse.from(productStopRequestRepository.findAllByStatus(status, oldest), AdminProductStopRequestDto::from);
    }
}
