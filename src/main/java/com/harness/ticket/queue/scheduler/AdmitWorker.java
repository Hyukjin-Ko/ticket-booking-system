package com.harness.ticket.queue.scheduler;

import com.harness.ticket.concert.domain.Concert;
import com.harness.ticket.concert.repository.ConcertRepository;
import com.harness.ticket.global.config.QueueProperties;
import com.harness.ticket.global.redis.RedisKeys;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdmitWorker {

    private static final Duration ADMIT_TTL = Duration.ofMinutes(10);

    private final ConcertRepository concertRepository;
    private final StringRedisTemplate redisTemplate;
    private final QueueProperties props;

    @Scheduled(fixedDelayString = "#{${queue.tick-interval-sec} * 1000}")
    public void tick() {
        runOnce();
    }

    public void runOnce() {
        List<Concert> active = concertRepository.findByQueueEnabledTrue();
        for (Concert c : active) {
            try {
                admitFor(c.getId());
            } catch (Exception e) {
                log.error("admit failed showId={}", c.getId(), e);
            }
        }
    }

    private void admitFor(Long showId) {
        String queueKey = RedisKeys.queue(showId);
        String admitKey = RedisKeys.admit(showId);

        Set<ZSetOperations.TypedTuple<String>> popped =
                redisTemplate.opsForZSet().popMin(queueKey, props.admitsPerTick());
        if (popped == null || popped.isEmpty()) {
            return;
        }

        for (ZSetOperations.TypedTuple<String> t : popped) {
            String userId = t.getValue();
            if (userId == null) {
                continue;
            }
            redisTemplate.opsForSet().add(admitKey, userId);
        }
        redisTemplate.expire(admitKey, ADMIT_TTL);
        log.info("admitted showId={} count={}", showId, popped.size());
    }
}
