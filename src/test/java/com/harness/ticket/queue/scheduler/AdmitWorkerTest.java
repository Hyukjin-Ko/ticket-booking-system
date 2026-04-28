package com.harness.ticket.queue.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.harness.ticket.concert.domain.Concert;
import com.harness.ticket.concert.repository.ConcertRepository;
import com.harness.ticket.global.config.QueueProperties;
import com.harness.ticket.global.redis.RedisKeys;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

@ExtendWith(MockitoExtension.class)
class AdmitWorkerTest {

    private static final Instant NOW = Instant.parse("2026-04-28T12:00:00Z");
    private static final Instant SHOW_STARTS_AT = NOW.plus(Duration.ofDays(30));

    @Mock
    private ConcertRepository concertRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    private final QueueProperties props = new QueueProperties(1, 100);

    private AdmitWorker worker;

    @BeforeEach
    void setUp() {
        worker = new AdmitWorker(concertRepository, redisTemplate, props);
    }

    private static Concert concertWithId(Long id) throws Exception {
        Concert c = Concert.create("title", SHOW_STARTS_AT, true, Clock.fixed(NOW, ZoneOffset.UTC));
        Field f = Concert.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(c, id);
        return c;
    }

    private static Set<ZSetOperations.TypedTuple<String>> tuples(String... userIds) {
        Set<ZSetOperations.TypedTuple<String>> out = new LinkedHashSet<>();
        double score = 1.0;
        for (String u : userIds) {
            out.add(new DefaultTypedTuple<>(u, score++));
        }
        return out;
    }

    @Test
    void runOnce_singleConcert_popMinAdmitsAllAndSetsTtl() throws Exception {
        Long showId = 2L;
        given(concertRepository.findByQueueEnabledTrue())
                .willReturn(List.of(concertWithId(showId)));
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(zSetOperations.popMin(eq(RedisKeys.queue(showId)), eq((long) props.admitsPerTick())))
                .willReturn(tuples("1", "2", "3", "4", "5"));

        worker.runOnce();

        verify(zSetOperations).popMin(RedisKeys.queue(showId), props.admitsPerTick());
        verify(setOperations).add(RedisKeys.admit(showId), "1");
        verify(setOperations).add(RedisKeys.admit(showId), "2");
        verify(setOperations).add(RedisKeys.admit(showId), "3");
        verify(setOperations).add(RedisKeys.admit(showId), "4");
        verify(setOperations).add(RedisKeys.admit(showId), "5");
        verify(redisTemplate).expire(eq(RedisKeys.admit(showId)), eq(Duration.ofMinutes(10)));
    }

    @Test
    void runOnce_twoConcerts_admitsBoth() throws Exception {
        Long showA = 2L;
        Long showB = 3L;
        given(concertRepository.findByQueueEnabledTrue())
                .willReturn(List.of(concertWithId(showA), concertWithId(showB)));
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(zSetOperations.popMin(eq(RedisKeys.queue(showA)), anyLong()))
                .willReturn(tuples("10"));
        given(zSetOperations.popMin(eq(RedisKeys.queue(showB)), anyLong()))
                .willReturn(tuples("20", "21"));

        worker.runOnce();

        verify(setOperations).add(RedisKeys.admit(showA), "10");
        verify(setOperations).add(RedisKeys.admit(showB), "20");
        verify(setOperations).add(RedisKeys.admit(showB), "21");
        verify(redisTemplate).expire(eq(RedisKeys.admit(showA)), eq(Duration.ofMinutes(10)));
        verify(redisTemplate).expire(eq(RedisKeys.admit(showB)), eq(Duration.ofMinutes(10)));
    }

    @Test
    void runOnce_emptyQueue_doesNotCallSaddOrExpire() throws Exception {
        Long showId = 2L;
        given(concertRepository.findByQueueEnabledTrue())
                .willReturn(List.of(concertWithId(showId)));
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(zSetOperations.popMin(anyString(), anyLong())).willReturn(Set.of());

        worker.runOnce();

        verify(redisTemplate, never()).opsForSet();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void runOnce_popMinReturnsNull_doesNotCallSaddOrExpire() throws Exception {
        Long showId = 2L;
        given(concertRepository.findByQueueEnabledTrue())
                .willReturn(List.of(concertWithId(showId)));
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(zSetOperations.popMin(anyString(), anyLong())).willReturn(null);

        worker.runOnce();

        verify(redisTemplate, never()).opsForSet();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void runOnce_oneConcertThrows_otherConcertStillProcessed() throws Exception {
        Long failingShow = 2L;
        Long okShow = 3L;
        given(concertRepository.findByQueueEnabledTrue())
                .willReturn(List.of(concertWithId(failingShow), concertWithId(okShow)));
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(zSetOperations.popMin(eq(RedisKeys.queue(failingShow)), anyLong()))
                .willThrow(new RuntimeException("redis down"));
        given(zSetOperations.popMin(eq(RedisKeys.queue(okShow)), anyLong()))
                .willReturn(tuples("99"));

        worker.runOnce();

        verify(setOperations, never()).add(eq(RedisKeys.admit(failingShow)), anyString());
        verify(setOperations, times(1)).add(RedisKeys.admit(okShow), "99");
        verify(redisTemplate).expire(eq(RedisKeys.admit(okShow)), eq(Duration.ofMinutes(10)));
    }
}
