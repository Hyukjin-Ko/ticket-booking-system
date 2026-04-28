package com.harness.ticket.reservation.payment;

import java.util.Random;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MockPaymentGatewayAdapter implements PaymentGateway {

    private static final String FORCE_SUCCESS = "success";
    private static final String FORCE_FAIL = "fail";
    private static final int RANDOM_BOUND = 10;
    private static final int SUCCESS_RATE_OUT_OF_10 = 9;

    private final Random random;

    @Override
    public boolean pay(Long reservationId, String mockResultHeader) {
        if (FORCE_SUCCESS.equals(mockResultHeader)) {
            log.info("mock payment FORCED success reservationId={}", reservationId);
            return true;
        }
        if (FORCE_FAIL.equals(mockResultHeader)) {
            log.info("mock payment FORCED fail reservationId={}", reservationId);
            return false;
        }
        boolean ok = random.nextInt(RANDOM_BOUND) < SUCCESS_RATE_OUT_OF_10;
        log.info("mock payment random reservationId={} result={}", reservationId, ok ? "success" : "fail");
        return ok;
    }
}
