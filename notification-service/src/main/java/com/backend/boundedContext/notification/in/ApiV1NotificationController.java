package com.backend.boundedContext.notification.in;

import com.backend.boundedContext.notification.app.NotificationFacade;
import com.backend.boundedContext.notification.app.dto.NotificationListDto;
import com.backend.boundedContext.notification.domain.NotificationType;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "알림", description = "내 알림 목록, 안 읽은 알림 개수, 읽음 처리")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class ApiV1NotificationController {

    private final NotificationFacade notificationFacade;

    @Operation(
            summary = "알림 목록",
            description = "최신순입니다. 다음 페이지는 응답의 nextCursor를 cursor로 넘겨 조회합니다. "
                    + "type을 넘기면 그 유형만 조회하고, 다음 페이지도 같은 type으로 조회합니다.")
    @GetMapping
    public RsData<NotificationListDto> getNotifications(
            @AuthenticationPrincipal AuthMember authMember,
            @Parameter(description = "알림 유형 (없으면 전체)") @RequestParam(required = false) NotificationType type,
            @Parameter(description = "이전 응답의 nextCursor (없으면 처음부터)") @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return RsData.of("200-1", "알림 목록을 조회했습니다.", notificationFacade.getNotifications(authMember.memberId(), type, cursor, size));
    }

    @Operation(summary = "안 읽은 알림 개수", description = "헤더 종 아이콘의 뱃지에 표시합니다. 유형과 관계없이 전체 개수입니다.")
    @GetMapping("/unread-count")
    public RsData<Long> getUnreadCount(@AuthenticationPrincipal AuthMember authMember) {
        return RsData.of("200-1", "안 읽은 알림 개수를 조회했습니다.", notificationFacade.getUnreadCount(authMember.memberId()));
    }

    @Operation(summary = "알림 읽음 처리", description = "이미 읽은 알림이면 그대로 성공합니다.")
    @ApiResponse(responseCode = "200", description = "200-1 읽음 처리")
    @ApiResponse(responseCode = "404", description = "404-NOTIFICATION_NOT_FOUND 없거나 다른 회원의 알림")
    @PatchMapping("/{notificationId}/read")
    public RsData<Void> read(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long notificationId
    ) {
        notificationFacade.read(authMember.memberId(), notificationId);
        return RsData.of("200-1", "알림을 읽음 처리했습니다.");
    }

    @Operation(summary = "알림 모두 읽음 처리", description = "안 읽은 내 알림을 모두 읽음 처리합니다. 안 읽은 알림이 없어도 성공합니다.")
    @PatchMapping("/read-all")
    public RsData<Void> readAll(@AuthenticationPrincipal AuthMember authMember) {
        notificationFacade.readAll(authMember.memberId());
        return RsData.of("200-1", "알림을 모두 읽음 처리했습니다.");
    }
}
