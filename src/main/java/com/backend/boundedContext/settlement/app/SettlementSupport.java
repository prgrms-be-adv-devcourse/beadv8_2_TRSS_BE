package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SettlementSupport {

    private final SettlementCandidateRepository settlementCandidateRepository;

    public boolean hasAnyCandidate() {
        return settlementCandidateRepository.count() > 0;
    }
}
