package com.harness.ticket.reservation.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

class MockPaymentGatewayAdapterTest {

    @Test
    void headerSuccess_returnsTrue_andDoesNotConsumeRandom() {
        Random random = new Random(9); // seed 9 → first nextInt(10) = 9 (fail)
        MockPaymentGatewayAdapter adapter = new MockPaymentGatewayAdapter(random);

        assertThat(adapter.pay(1L, "success")).isTrue();
        // random은 헤더 강제 모드에서 호출되지 않아야 한다 — 다음 호출이 여전히 첫 값(9)이어야 함
        assertThat(random.nextInt(10)).isEqualTo(9);
    }

    @Test
    void headerFail_returnsFalse_andDoesNotConsumeRandom() {
        Random random = new Random(42); // seed 42 → first nextInt(10) = 0 (success)
        MockPaymentGatewayAdapter adapter = new MockPaymentGatewayAdapter(random);

        assertThat(adapter.pay(1L, "fail")).isFalse();
        assertThat(random.nextInt(10)).isEqualTo(0);
    }

    @Test
    void headerNullWithSeed42_returnsTrue() {
        // new Random(42).nextInt(10) == 0 → 0 < 9 이므로 success
        Random random = new Random(42);
        MockPaymentGatewayAdapter adapter = new MockPaymentGatewayAdapter(random);

        assertThat(adapter.pay(1L, null)).isTrue();
    }

    @Test
    void headerNullWithSeed9_returnsFalse() {
        // new Random(9).nextInt(10) == 9 → 9 < 9 false → fail
        Random random = new Random(9);
        MockPaymentGatewayAdapter adapter = new MockPaymentGatewayAdapter(random);

        assertThat(adapter.pay(1L, null)).isFalse();
    }

    @Test
    void unknownHeader_fallsThroughToRandom() {
        Random random = new Random(42);
        MockPaymentGatewayAdapter adapter = new MockPaymentGatewayAdapter(random);

        // "unknown" 헤더는 success/fail이 아니므로 random fallback → seed 42 → success
        assertThat(adapter.pay(1L, "unknown")).isTrue();
    }
}
