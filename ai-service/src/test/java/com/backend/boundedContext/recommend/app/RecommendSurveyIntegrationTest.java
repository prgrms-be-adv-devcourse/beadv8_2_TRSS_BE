package com.backend.boundedContext.recommend.app;

import com.backend.boundedContext.recommend.app.dto.BeautyProfileDto;
import com.backend.boundedContext.recommend.domain.BeautyCategory;
import com.backend.boundedContext.recommend.domain.RecommendErrorCode;
import com.backend.boundedContext.recommend.domain.SkinConcern;
import com.backend.boundedContext.recommend.domain.SkinType;
import com.backend.boundedContext.recommend.out.BeautyProfileRepository;
import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static com.backend.boundedContext.recommend.domain.IngredientTag.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 DB(text[] 컬럼)로 설문 저장·조회. ddl-auto: validate가 배열 매핑을 통과하는지도 함께 본다 */
@SpringBootTest
class RecommendSurveyIntegrationTest {

    private static final Long MEMBER_ID = 9_001L;

    @Autowired
    private RecommendFacade recommendFacade;

    @Autowired
    private BeautyProfileRepository beautyProfileRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        beautyProfileRepository.deleteAll();
    }

    @Test
    @DisplayName("두 번 저장하면 회원당 1행이 남고 마지막 답으로 덮어씀. DB에는 코드 이름이 text[]로 저장됨")
    void saveTwiceKeepsOneRow() {
        recommendFacade.saveSurvey(MEMBER_ID, SkinType.OILY, List.of(SkinConcern.TROUBLE),
                List.of(CICA), List.of(ALCOHOL), List.of(BeautyCategory.SKINCARE));
        BeautyProfileDto saved = recommendFacade.saveSurvey(MEMBER_ID, SkinType.DRY, List.of(SkinConcern.DRYNESS, SkinConcern.PORE),
                List.of(), List.of(FRAGRANCE, PARABEN), List.of());

        assertThat(beautyProfileRepository.count()).isEqualTo(1);
        assertThat(saved.skinType()).isEqualTo(SkinType.DRY);
        assertThat(saved.updatedAt()).isNotNull();
        assertThat(recommendFacade.getSurvey(MEMBER_ID).concerns()).containsExactly(SkinConcern.PORE, SkinConcern.DRYNESS);

        List<String> avoided = jdbcTemplate.queryForList(
                "select unnest(avoided_ingredients) from beauty_profile where member_id = ?", String.class, MEMBER_ID);
        assertThat(avoided).containsExactly("FRAGRANCE", "PARABEN");
    }

    @Test
    @DisplayName("설문을 안 한 회원 조회는 SURVEY_NOT_FOUND")
    void getWithoutSurvey() {
        assertThatThrownBy(() -> recommendFacade.getSurvey(MEMBER_ID))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(RecommendErrorCode.SURVEY_NOT_FOUND.resultCode());
    }
}
