package com.harness.ticket.concert.service;

import com.harness.ticket.concert.dto.ConcertResponse;
import com.harness.ticket.concert.dto.SeatResponse;
import com.harness.ticket.concert.repository.ConcertRepository;
import com.harness.ticket.concert.repository.SeatRepository;
import com.harness.ticket.global.exception.BusinessException;
import com.harness.ticket.global.response.ErrorCode;
import com.harness.ticket.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConcertQueryService {

    private final ConcertRepository concertRepository;
    private final SeatRepository seatRepository;

    public PageResponse<ConcertResponse> findAll(Pageable pageable) {
        Page<ConcertResponse> p = concertRepository.findAll(pageable).map(ConcertResponse::from);
        return PageResponse.from(p);
    }

    public PageResponse<SeatResponse> findSeats(Long concertId, Pageable pageable) {
        if (!concertRepository.existsById(concertId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "공연을 찾을 수 없습니다");
        }
        Page<SeatResponse> p = seatRepository.findByConcertId(concertId, pageable).map(SeatResponse::from);
        return PageResponse.from(p);
    }
}
