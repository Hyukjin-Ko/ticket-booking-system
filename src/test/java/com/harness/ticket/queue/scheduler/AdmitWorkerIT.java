package com.harness.ticket.queue.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.support.IntegrationTestSupport;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

// tick-interval-sec를 3600초로 설정해 @Scheduled 자동 실행이 테스트 중 발생하지 않게 한다.
// 워커 동작 검증은 worker.runOnce() 직접 호출로만 수행 — 결정성 확보.
@TestPropertySource(properties = {
        "queue.tick-interval-sec=3600",
        "queue.admits-per-tick=2"
})
class AdmitWorkerIT extends IntegrationTestSupport {

    private static final Long QUEUED_SHOW_ID = 2L;
    private static final Long NON_QUEUED_SHOW_ID = 1L;

    @Autowired
    private AdmitWorker worker;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanState() {
        flushRedis();
    }

    @AfterEach
    void tearDown() {
        flushRedis();
    }

    @Test
    void runOnce_admitsPerTick2_drainsQueueOverThreeTicks() {
        seedQueue(QUEUED_SHOW_ID, "100", "101", "102", "103", "104");

        worker.runOnce();

        assertThat(setMembers(RedisKeys.admit(QUEUED_SHOW_ID))).containsExactlyInAnyOrder("100", "101");
        assertThat(zSetSize(RedisKeys.queue(QUEUED_SHOW_ID))).isEqualTo(3L);

        worker.runOnce();

        assertThat(setMembers(RedisKeys.admit(QUEUED_SHOW_ID)))
                .containsExactlyInAnyOrder("100", "101", "102", "103");
        assertThat(zSetSize(RedisKeys.queue(QUEUED_SHOW_ID))).isEqualTo(1L);

        worker.runOnce();

        assertThat(setMembers(RedisKeys.admit(QUEUED_SHOW_ID)))
                .containsExactlyInAnyOrder("100", "101", "102", "103", "104");
        assertThat(zSetSize(RedisKeys.queue(QUEUED_SHOW_ID))).isZero();
    }

    @Test
    void runOnce_queueEnabledFalseConcert_isIgnored() {
        seedQueue(NON_QUEUED_SHOW_ID, "200", "201", "202");

        worker.runOnce();

        // queueEnabled=false 공연은 워커가 무시 — queue 그대로, admit 비어있음
        assertThat(zSetSize(RedisKeys.queue(NON_QUEUED_SHOW_ID))).isEqualTo(3L);
        Set<String> admit = setMembers(RedisKeys.admit(NON_QUEUED_SHOW_ID));
        assertThat(admit == null || admit.isEmpty()).isTrue();
    }

    @Test
    void runOnce_admitSet_hasTtlAround600Seconds() {
        seedQueue(QUEUED_SHOW_ID, "300", "301");

        worker.runOnce();

        Long ttl = redisTemplate.getExpire(RedisKeys.admit(QUEUED_SHOW_ID), TimeUnit.SECONDS);
        assertThat(ttl).isNotNull();
        // ADR-005: admit Set TTL = 10분 = 600초. 약간의 실행 지연을 허용.
        assertThat(ttl).isBetween(590L, 600L);
    }

    @Test
    void runOnce_emptyQueue_doesNothing() {
        // queue 비어있는 상태에서 워커 실행 — 예외 없이 종료, admit Set도 만들어지지 않음
        worker.runOnce();

        Set<String> admit = setMembers(RedisKeys.admit(QUEUED_SHOW_ID));
        assertThat(admit == null || admit.isEmpty()).isTrue();
    }

    private void seedQueue(Long showId, String... userIds) {
        String key = RedisKeys.queue(showId);
        double score = 1.0;
        for (String u : userIds) {
            redisTemplate.opsForZSet().add(key, u, score);
            score += 1.0;
        }
    }

    private Long zSetSize(String key) {
        Long size = redisTemplate.opsForZSet().zCard(key);
        return size == null ? 0L : size;
    }

    private Set<String> setMembers(String key) {
        return redisTemplate.opsForSet().members(key);
    }

    private void flushRedis() {
        deleteByPattern("queue:*");
        deleteByPattern("admit:*");
        deleteByPattern("seat:*");
        deleteByPattern("count:user:*");
        deleteByPattern("refresh:*");
    }

    private void deleteByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
