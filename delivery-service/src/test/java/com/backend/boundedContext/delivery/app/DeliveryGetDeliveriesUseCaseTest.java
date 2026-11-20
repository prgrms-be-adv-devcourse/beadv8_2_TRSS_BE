package com.backend.boundedContext.delivery.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.backend.boundedContext.delivery.DeliveryFixtures;
import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.boundedContext.delivery.in.dto.LogisticsDeliveryResponse;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.boundedContext.delivery.out.ProductImagePort;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.page.PageResponse;
import com.backend.shared.delivery.dto.DeliveryStatus;

/**
 * 물류 배송 목록의 상품 대표 이미지. market(ProductImagePort)은 가짜로 바꾼다
 * 픽스처 항목의 productId = orderItemId + 1000, optionId = orderItemId + 2000
 */
class DeliveryGetDeliveriesUseCaseTest {

    private static final Pageable PAGE = PageRequest.of(0, 20);

    private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
    private final ProductImagePort productImagePort = mock(ProductImagePort.class);
    private final DeliveryGetDeliveriesUseCase useCase =
            new DeliveryGetDeliveriesUseCase(deliveryRepository, new DeliveryProductImageSupport(productImagePort));

    // 배송 2건: 항목 orderItemId 1·2·3, 11·12·13
    private final List<Delivery> deliveries = List.of(
            Delivery.create(DeliveryFixtures.paidEvent(100L, 1L)),
            Delivery.create(DeliveryFixtures.paidEvent(101L, 11L)));

    @BeforeEach
    void setUp() {
        given(deliveryRepository.findByStatus(eq(DeliveryStatus.READY), any(Pageable.class)))
                .willReturn(new PageImpl<>(deliveries, PAGE, deliveries.size()));
    }

    @Test
    @DisplayName("한 페이지 모든 항목의 옵션 ID를 모아 market을 한 번만 부르고, 항목마다 productId·thumbnailUrl을 붙인다")
    void mapsThumbnails() {
        given(productImagePort.getThumbnailUrls(anyCollection())).willReturn(Map.of(
                2001L, "https://img/1.jpg",
                2013L, "https://img/13.jpg"));

        PageResponse<LogisticsDeliveryResponse> result = useCase.getDeliveries(null, PAGE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> optionIds = ArgumentCaptor.forClass(Collection.class);
        then(productImagePort).should(times(1)).getThumbnailUrls(optionIds.capture());
        assertThat(optionIds.getValue()).containsExactlyInAnyOrder(2001L, 2002L, 2003L, 2011L, 2012L, 2013L);

        assertThat(result.items().get(0).items())
                .extracting(LogisticsDeliveryResponse.Item::orderItemId, LogisticsDeliveryResponse.Item::productId,
                        LogisticsDeliveryResponse.Item::thumbnailUrl)
                .containsExactly(
                        tuple(1L, 1001L, "https://img/1.jpg"),
                        tuple(2L, 1002L, null),
                        tuple(3L, 1003L, null));
        assertThat(result.items().get(1).items())
                .extracting(LogisticsDeliveryResponse.Item::thumbnailUrl)
                .containsExactly(null, null, "https://img/13.jpg");
    }

    @Test
    @DisplayName("market 호출이 실패하면 이미지 없이(thumbnailUrl null) 목록을 정상 응답한다")
    void marketFailure_nullThumbnails() {
        given(productImagePort.getThumbnailUrls(anyCollection()))
                .willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));

        PageResponse<LogisticsDeliveryResponse> result = useCase.getDeliveries(DeliveryStatus.READY, PAGE);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items())
                .flatExtracting(LogisticsDeliveryResponse::items)
                .extracting(LogisticsDeliveryResponse.Item::productId, LogisticsDeliveryResponse.Item::thumbnailUrl)
                .containsExactly(
                        tuple(1001L, null), tuple(1002L, null), tuple(1003L, null),
                        tuple(1011L, null), tuple(1012L, null), tuple(1013L, null));
    }

    @Test
    @DisplayName("목록이 비어 있으면 market을 부르지 않는다")
    void emptyPage_noCall() {
        given(deliveryRepository.findByStatus(eq(DeliveryStatus.SHIPPED), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), PAGE, 0));

        PageResponse<LogisticsDeliveryResponse> result = useCase.getDeliveries(DeliveryStatus.SHIPPED, PAGE);

        assertThat(result.items()).isEmpty();
        then(productImagePort).should(never()).getThumbnailUrls(anyCollection());
    }
}
