package com.backend.boundedContext.notification.in;

import com.backend.boundedContext.notification.app.NotificationFacade;
import com.backend.boundedContext.notification.app.dto.NotificationListDto;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "알림", description = "내 알림 목록, 안 읽은 알림 개수")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class ApiV1NotificationController {

    private final NotificationFacade notificationFacade;

    @Operation(
            summary = "알림 목록",
            description = "최신순입니다. 다음 페이지는 응답의 nextCursor를 cursor로 넘겨 조회합니다.")
    @GetMapping
    public RsData<NotificationListDto> getNotifications(
            @AuthenticationPrincipal AuthMember authMember,
            @Parameter(description = "이전 응답의 nextCursor (없으면 처음부터)") @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return RsData.of("200-1", "알림 목록을 조회했습니다.", notificationFacade.getNotifications(authMember.memberId(), cursor, size));
    }

    @Operation(summary = "안 읽은 알림 개수", description = "헤더 종 아이콘의 뱃지에 표시합니다.")
    @GetMapping("/unread-count")
    public RsData<Long> getUnreadCount(@AuthenticationPrincipal AuthMember authMember) {
        return RsData.of("200-1", "안 읽은 알림 개수를 조회했습니다.", notificationFacade.getUnreadCount(authMember.memberId()));
    }
}
