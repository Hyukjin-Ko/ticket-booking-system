package com.harness.ticket.reservation.payment;

public interface PaymentGateway {

    /**
     * 결제 호출.
     *
     * @param reservationId    예약 식별자
     * @param mockResultHeader X-Mock-Pay-Result 헤더 값 ("success"|"fail"|null)
     * @return 결제 성공 시 true, 실패 시 false
     */
    boolean pay(Long reservationId, String mockResultHeader);
}
