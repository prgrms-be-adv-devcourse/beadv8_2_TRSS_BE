package com.backend.boundedContext.settlement;

import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 원장 무결성 1단계 규칙을 테스트로 강제한다 (운영 코드만 검사, 테스트 정리 코드는 제외)
 *   1. 정산 리포지토리(정산서·후보·처리 이력)의 delete* 메서드를 부르지 않는다 (엔티티 @PreRemove가 마지막 안전망)
 *   2. 정산 코드는 JdbcTemplate을 쓰지 않는다 (updatable = false·@Immutable을 우회하는 SQL을 막는다)
 */
@AnalyzeClasses(packages = "com.backend.boundedContext.settlement", importOptions = ImportOption.DoNotIncludeTests.class)
class SettlementLedgerIntegrityArchTest {

    private static final List<Class<?>> LEDGER_REPOSITORIES = List.of(
            SettlementRepository.class, SettlementCandidateRepository.class, SettlementProcessHistoryRepository.class);

    @ArchTest
    static final ArchRule noDeleteOnSettlementRepositories = noClasses()
            .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>("정산 리포지토리의 delete 메서드 호출") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return call.getName().startsWith("delete")
                            && LEDGER_REPOSITORIES.stream().anyMatch(repo -> call.getTargetOwner().isAssignableTo(repo));
                }
            })
            .because("정산 데이터는 지우지 않고 제외·보류·조정으로 정정한다(원장 무결성 1단계)");

    @ArchTest
    static final ArchRule noJdbcTemplateInSettlement = noClasses()
            .should().dependOnClassesThat().areAssignableTo(JdbcTemplate.class)
            .orShould().dependOnClassesThat().areAssignableTo(NamedParameterJdbcTemplate.class)
            .because("JPA 매핑(updatable = false, @Immutable)을 우회하는 SQL로 정산 금액을 바꾸지 않는다");
}
