package com.backend.boundedContext.chat.in;

import com.backend.boundedContext.chat.app.ChatFacade;
import com.backend.boundedContext.chat.app.dto.ChatAnswerDto;
import com.backend.boundedContext.chat.app.dto.ChatConversationDto;
import com.backend.boundedContext.chat.app.dto.ChatMessageDto;
import com.backend.boundedContext.chat.in.dto.ChatRequest;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "챗봇", description = "FAQ 근거 AI 챗봇")
@RestController
@RequestMapping("/api/v1/ai/chat")
@RequiredArgsConstructor
public class ApiV1ChatController {

    private final ChatFacade chatFacade;

    @Operation(
            summary = "챗봇 질문",
            description = "질문과 뜻이 가까운 FAQ를 찾아 그 FAQ만 근거로 답합니다. 로그인이 필요합니다. "
                    + "conversationId를 보내면 그 대화의 최근 질문·답변을 이어 받아 답하고, 없으면 새 대화를 시작합니다. "
                    + "answerType: AI(FAQ 근거로 AI가 답함), FAQ(AI가 실패해 가장 비슷한 FAQ 답변을 그대로 줌), "
                    + "NO_MATCH(관련 FAQ가 없어 고객센터 안내, sources는 빈 목록).")
    @ApiResponse(responseCode = "404", description = "404-CONVERSATION_NOT_FOUND 대화가 없거나 내 대화가 아님")
    @ApiResponse(responseCode = "429", description = "429-TOO_MANY_REQUESTS 회원별 질문 수 제한(1분에 10번)을 넘음. 첫 질문부터 1분이 지나면 다시 물을 수 있음")
    @ApiResponse(responseCode = "502", description = "502-EXTERNAL_SERVICE_ERROR 질문 임베딩(OpenAI) 실패로 FAQ를 찾지 못함")
    @PostMapping
    public RsData<ChatAnswerDto> chat(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody ChatRequest request
    ) {
        return RsData.of("200-1", "답변했습니다.", chatFacade.answer(authMember.memberId(), request.conversationId(), request.message().strip()));
    }

    @Operation(summary = "내 대화 목록", description = "최근 대화 순입니다. 제목은 첫 질문(100자까지)입니다. page(0부터), size(기본 20)를 받습니다.")
    @GetMapping("/conversations")
    public RsData<PageResponse<ChatConversationDto>> getConversations(
            @AuthenticationPrincipal AuthMember authMember,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "대화 목록을 조회했습니다.", chatFacade.getConversations(authMember.memberId(), pageable));
    }

    @Operation(summary = "대화 메시지", description = "대화의 질문·답변을 오래된 순으로 모두 줍니다. 근거 FAQ는 지금 FAQ의 카테고리·질문으로 보여 줍니다.")
    @ApiResponse(responseCode = "404", description = "404-CONVERSATION_NOT_FOUND 대화가 없거나 내 대화가 아님")
    @GetMapping("/conversations/{conversationId}/messages")
    public RsData<List<ChatMessageDto>> getMessages(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long conversationId
    ) {
        return RsData.of("200-1", "대화 메시지를 조회했습니다.", chatFacade.getMessages(authMember.memberId(), conversationId));
    }
}
