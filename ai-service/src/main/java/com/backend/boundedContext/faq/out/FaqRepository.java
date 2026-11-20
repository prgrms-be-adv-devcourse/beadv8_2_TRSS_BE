package com.backend.boundedContext.faq.out;

import com.backend.boundedContext.faq.domain.Faq;
import com.backend.boundedContext.faq.domain.FaqCategory;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FaqRepository extends JpaRepository<Faq, Long> {

    List<Faq> findAllByCategory(FaqCategory category, Sort sort);

    // 노출 중인 FAQ의 질문(등록 순). 챗봇 검색어 다시 쓰기에 FAQ 표현을 알려 줄 때 씀
    @Query("select f.question from Faq f where f.visible = true order by f.id")
    List<String> findVisibleQuestions();
}
