package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.app.dto.AdminInfluencerCountsDto;
import com.backend.boundedContext.member.app.dto.AdminInfluencerDto;
import com.backend.boundedContext.member.app.dto.AdminInfluencerListDto;
import com.backend.boundedContext.member.domain.InfluencerStatus;
import com.backend.boundedContext.member.out.InfluencerRepository;
import com.backend.boundedContext.member.out.InfluencerStatusCountRow;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class MemberGetInfluencersUseCase {
    private final InfluencerRepository influencerRepository;

    public AdminInfluencerListDto getInfluencers(InfluencerStatus status, Pageable pageable) {
        Pageable pageOnly = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return new AdminInfluencerListDto(
                PageResponse.from(influencerRepository.findAllForAdmin(status, pageOnly), AdminInfluencerDto::from),
                AdminInfluencerCountsDto.from(influencerRepository.countGroupByStatus().stream()
                        .collect(Collectors.toMap(InfluencerStatusCountRow::status, InfluencerStatusCountRow::count)))
        );
    }
}
