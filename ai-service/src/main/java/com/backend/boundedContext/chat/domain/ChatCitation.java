package com.backend.boundedContext.chat.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 답변 끝의 근거 표시([근거: 1, 3] / [근거: 없음])를 떼어 내고 실제로 근거로 쓴 FAQ 번호를 읽음
 *
 * @param answer     근거 표시를 뗀 답변
 * @param faqNumbers 근거로 쓴 FAQ 번호([FAQ] 목록의 1부터 시작하는 번호, 중복 없이). 빈 목록이면 FAQ로 답하지 못함. null이면 표시가 없거나 읽을 수 없음
 */
public record ChatCitation(
        String answer,
        List<Integer> faqNumbers
) {
    private static final Pattern MARK = Pattern.compile("\\[근거:\\s*([^\\]]*)]\\s*$");
    private static final String NONE = "없음";

    // faqCount: 프롬프트에 넣은 FAQ 수. 범위 밖 번호가 있으면 읽을 수 없는 것으로 봄
    public static ChatCitation parse(String text, int faqCount) {
        Matcher m = MARK.matcher(text);
        if (!m.find()) return new ChatCitation(text.strip(), null);

        String answer = text.substring(0, m.start()).strip();
        String value = m.group(1).strip();
        if (value.equals(NONE)) return new ChatCitation(answer, List.of());

        List<Integer> numbers = new ArrayList<>();
        for (String part : value.split(",")) {
            Integer number = toNumber(part.strip(), faqCount);
            if (number == null) return new ChatCitation(answer, null);
            if (!numbers.contains(number)) numbers.add(number);
        }
        return new ChatCitation(answer, numbers);
    }

    private static Integer toNumber(String part, int faqCount) {
        try {
            int number = Integer.parseInt(part);
            return number >= 1 && number <= faqCount ? number : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
