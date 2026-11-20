package com.backend.boundedContext.chat.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param conversationId 이어서 물을 대화. 없으면(null) 새 대화를 시작함
 * @param message        사용자 질문. 앞뒤 공백을 지우고 씀
 */
public record ChatRequest(
        Long conversationId,
        @NotBlank @Size(max = 200) String message
) {}
