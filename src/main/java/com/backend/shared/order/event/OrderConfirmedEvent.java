package com.backend.shared.order.event;

import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.ConfirmedOrderItemDto;

import java.time.LocalDateTime;
import java.util.List;

public record OrderConfirmedEvent(Long orderId, Long buyerId, LocalDateTime confirmedAt,
                                  ConfirmType confirmType, List<ConfirmedOrderItemDto> items) { }
