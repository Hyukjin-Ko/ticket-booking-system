package com.harness.ticket.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.harness.ticket.concert.domain.Concert;
import com.harness.ticket.concert.repository.ConcertRepository;
import com.harness.ticket.global.config.QueueProperties;
import com.harness.ticket.global.exception.BusinessException;
import com.harness.ticket.global.redis.RedisKeys;
import com.harness.ticket.global.response.ErrorCode;
import com.harness.ticket.queue.dto.QueueStatusResponse;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-28T12:00:00Z");
    private static final Instant SHOW_STARTS_AT = NOW.plus(Duration.ofDays(30));
    private static final Long SHOW_ID = 2L;
    private static final Long USER_ID = 7L;

    @Mock
    private ConcertRepository concertRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    private final QueueProperties props = new QueueProperties(1, 100);

    private QueueService service;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new QueueService(concertRepository, redisTemplate, fixedClock, props);
    }

    private static Concert concertWithId(Long id, boolean queueEnabled, Instant startsAt) throws Exception {
        Concert c = Concert.create("title", startsAt, queueEnabled, Clock.fixed(NOW, ZoneOffset.UTC));
        Field f = Concert.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(c, id);
        return c;
    }

    @Test
    void enter_queueEnabled_addsToZSet_setsExpire_returnsStatus() throws Exception {
        given(concertRepository.findById(SHOW_ID))
                .willReturn(Optional.of(concertWithId(SHOW_ID, true, SHOW_STARTS_AT)));
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(setOperations.isMember(RedisKeys.admit(SHOW_ID), USER_ID.toString())).willReturn(false);
        given(zSetOperations.rank(RedisKeys.queue(SHOW_ID), USER_ID.toString())).willReturn(0L);

        QueueStatusResponse res = service.enter(SHOW_ID, USER_ID);

        verify(zSetOperations).addIfAbsent(
                eq(RedisKeys.queue(SHOW_ID)),
                eq(USER_ID.toString()),
                eq((double) NOW.toEpochMilli()));
        verify(redisTemplate).expire(eq(RedisKeys.queue(SHOW_ID)), any(Duration.class));
        assertThat(res.position()).isZero();
        assertThat(res.etaSec()).isEqualTo(1L);
        assertThat(res.admitted()).isFalse();
    }

    @Test
    void enter_queueDisabled_throwsQueueNotEnabled() throws Exception {
        given(concertRepository.findById(SHOW_ID))
                .willReturn(Optional.of(concertWithId(SHOW_ID, false, SHOW_STARTS_AT)));

        assertThatThrownBy(() -> service.enter(SHOW_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.QUEUE_NOT_ENABLED);

        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    void enter_afterShowStartsPlusOneHour_throwsQueueNotEnabled() throws Exception {
        // Clock 시각을 공연 시작 + 1시간 + 1초 이후로 설정
        Instant afterExpiry = SHOW_STARTS_AT.plus(Duration.ofHours(1)).plusSeconds(1);
        Clock lateClock = Clock.fixed(afterExpiry, ZoneOffset.UTC);
        QueueService lateService = new QueueService(concertRepository, redisTemplate, lateClock, props);

        given(concertRepository.findById(SHOW_ID))
                .willReturn(Optional.of(concertWithId(SHOW_ID, true, SHOW_STARTS_AT)));

        assertThatThrownBy(() -> lateService.enter(SHOW_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.QUEUE_NOT_ENABLED);

        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    void enter_concertNotFound_throwsNotFound() {
        given(concertRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.enter(99L, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void enter_sameUserTwice_callsAddIfAbsentTwice_keepsSamePosition() throws Exception {
        given(concertRepository.findById(SHOW_ID))
                .willReturn(Optional.of(concertWithId(SHOW_ID, true, SHOW_STARTS_AT)));
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(setOperations.isMember(RedisKeys.admit(SHOW_ID), USER_ID.toString())).willReturn(false);
        given(zSetOperations.rank(RedisKeys.queue(SHOW_ID), USER_ID.toString())).willReturn(0L);
        // first call returns true (added), second returns false (already present, NX semantics)
        given(zSetOperations.addIfAbsent(anyString(), anyString(), any(Double.class)))
                .willReturn(true).willReturn(false);

        QueueStatusResponse first = service.enter(SHOW_ID, USER_ID);
        QueueStatusResponse second = service.enter(SHOW_ID, USER_ID);

        verify(zSetOperations, times(2)).addIfAbsent(
                eq(RedisKeys.queue(SHOW_ID)),
                eq(USER_ID.toString()),
                eq((double) NOW.toEpochMilli()));
        assertThat(first.position()).isEqualTo(second.position()).isZero();
    }

    @Test
    void getMyStatus_admitted_returnsZeroEtaTrue() throws Exception {
        given(concertRepository.findById(SHOW_ID))
                .willReturn(Optional.of(concertWithId(SHOW_ID, true, SHOW_STARTS_AT)));
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(setOperations.isMember(RedisKeys.admit(SHOW_ID), USER_ID.toString())).willReturn(true);

        QueueStatusResponse res = service.getMyStatus(SHOW_ID, USER_ID);

        assertThat(res.position()).isZero();
        assertThat(res.etaSec()).isZero();
        assertThat(res.admitted()).isTrue();
        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    void getMyStatus_inQueueRankFive_returnsRankAndEta() throws Exception {
        given(concertRepository.findById(SHOW_ID))
                .willReturn(Optional.of(concertWithId(SHOW_ID, true, SHOW_STARTS_AT)));
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(setOperations.isMember(RedisKeys.admit(SHOW_ID), USER_ID.toString())).willReturn(false);
        given(zSetOperations.rank(RedisKeys.queue(SHOW_ID), USER_ID.toString())).willReturn(5L);

        QueueStatusResponse res = service.getMyStatus(SHOW_ID, USER_ID);

        assertThat(res.position()).isEqualTo(5);
        assertThat(res.etaSec()).isEqualTo(1L);
        assertThat(res.admitted()).isFalse();
    }

    @Test
    void getMyStatus_rank99_eta1Sec_rank100_eta2Sec_boundary() throws Exception {
        given(concertRepository.findById(SHOW_ID))
                .willReturn(Optional.of(concertWithId(SHOW_ID, true, SHOW_STARTS_AT)));
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(setOperations.isMember(RedisKeys.admit(SHOW_ID), USER_ID.toString())).willReturn(false);
        given(zSetOperations.rank(RedisKeys.queue(SHOW_ID), USER_ID.toString()))
                .willReturn(99L)
                .willReturn(100L);

        QueueStatusResponse res99 = service.getMyStatus(SHOW_ID, USER_ID);
        QueueStatusResponse res100 = service.getMyStatus(SHOW_ID, USER_ID);

        assertThat(res99.position()).isEqualTo(99);
        assertThat(res99.etaSec()).isEqualTo(1L);
        assertThat(res100.position()).isEqualTo(100);
        assertThat(res100.etaSec()).isEqualTo(2L);
    }

    @Test
    void getMyStatus_notInQueueAndNotAdmitted_throwsNotInQueue() throws Exception {
        given(concertRepository.findById(SHOW_ID))
                .willReturn(Optional.of(concertWithId(SHOW_ID, true, SHOW_STARTS_AT)));
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(setOperations.isMember(RedisKeys.admit(SHOW_ID), USER_ID.toString())).willReturn(false);
        given(zSetOperations.rank(RedisKeys.queue(SHOW_ID), USER_ID.toString())).willReturn(null);

        assertThatThrownBy(() -> service.getMyStatus(SHOW_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_IN_QUEUE);
    }

    @Test
    void getMyStatus_queueDisabled_throwsQueueNotEnabled() throws Exception {
        given(concertRepository.findById(SHOW_ID))
                .willReturn(Optional.of(concertWithId(SHOW_ID, false, SHOW_STARTS_AT)));

        assertThatThrownBy(() -> service.getMyStatus(SHOW_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.QUEUE_NOT_ENABLED);

        verify(redisTemplate, never()).opsForZSet();
        verify(redisTemplate, never()).opsForSet();
    }
}
