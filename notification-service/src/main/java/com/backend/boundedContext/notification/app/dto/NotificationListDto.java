package com.backend.boundedContext.notification.app.dto;

import java.util.List;

/**
 * 알림 목록(커서 방식). 다음 페이지는 nextCursor를 cursor로 넘겨 조회함
 *
 * @param items      최신순 알림
 * @param nextCursor 다음 요청의 cursor(이번 목록의 마지막 알림 id). 더 없으면 null
 * @param hasNext    다음 페이지가 있으면 true
 */
public record NotificationListDto(
        List<NotificationDto> items,
        Long nextCursor,
        boolean hasNext
) {}
