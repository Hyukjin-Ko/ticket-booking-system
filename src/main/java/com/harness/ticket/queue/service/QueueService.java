package com.harness.ticket.queue.service;

import com.harness.ticket.concert.domain.Concert;
import com.harness.ticket.concert.repository.ConcertRepository;
import com.harness.ticket.global.config.QueueProperties;
import com.harness.ticket.global.exception.BusinessException;
import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.global.response.ErrorCode;
import com.harness.ticket.queue.dto.QueueStatusResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private static final Duration QUEUE_TTL_BUFFER = Duration.ofHours(1);

    private final ConcertRepository concertRepository;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final QueueProperties props;

    public QueueStatusResponse enter(Long showId, Long userId) {
        Concert concert = concertRepository.findById(showId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연을 찾을 수 없습니다"));

        Instant now = Instant.now(clock);
        Instant queueExpiresAt = concert.getStartsAt().plus(QUEUE_TTL_BUFFER);
        if (!concert.isQueueEnabled() || !now.isBefore(queueExpiresAt)) {
            throw new BusinessException(ErrorCode.QUEUE_NOT_ENABLED);
        }

        String queueKey = RedisKeys.queue(showId);
        long score = now.toEpochMilli();
        redisTemplate.opsForZSet().addIfAbsent(queueKey, userId.toString(), score);

        long ttlSec = Duration.between(now, queueExpiresAt).getSeconds();
        if (ttlSec > 0) {
            redisTemplate.expire(queueKey, Duration.ofSeconds(ttlSec));
        }

        log.info("queue enter showId={} userId={}", showId, userId);
        return buildStatus(showId, userId);
    }

    public QueueStatusResponse getMyStatus(Long showId, Long userId) {
        Concert concert = concertRepository.findById(showId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연을 찾을 수 없습니다"));
        if (!concert.isQueueEnabled()) {
            throw new BusinessException(ErrorCode.QUEUE_NOT_ENABLED);
        }
        return buildStatus(showId, userId);
    }

    private QueueStatusResponse buildStatus(Long showId, Long userId) {
        String admitKey = RedisKeys.admit(showId);
        Boolean admitted = redisTemplate.opsForSet().isMember(admitKey, userId.toString());
        if (Boolean.TRUE.equals(admitted)) {
            return new QueueStatusResponse(0, 0L, true);
        }

        String queueKey = RedisKeys.queue(showId);
        Long rank = redisTemplate.opsForZSet().rank(queueKey, userId.toString());
        if (rank == null) {
            throw new BusinessException(ErrorCode.NOT_IN_QUEUE);
        }

        int position = rank.intValue();
        long etaSec = computeEta(position);
        return new QueueStatusResponse(position, etaSec, false);
    }

    private long computeEta(int rank) {
        int admitsPerTick = props.admitsPerTick();
        int tickIntervalSec = props.tickIntervalSec();
        long ticksAhead = ((long) rank / admitsPerTick) + 1;
        return ticksAhead * tickIntervalSec;
    }
}
