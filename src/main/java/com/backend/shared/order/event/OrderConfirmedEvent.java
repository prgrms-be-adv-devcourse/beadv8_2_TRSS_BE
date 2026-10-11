package com.backend.shared.order.event;

import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.ConfirmedOrderItemDto;

import java.time.LocalDateTime;
import java.util.List;

// TODO 정산 이벤트를 위해 임시 구현. 추후 수정
public record OrderConfirmedEvent(Long orderId, Long buyerId, LocalDateTime confirmedAt,
                                  ConfirmType confirmType, List<ConfirmedOrderItemDto> items) { }
