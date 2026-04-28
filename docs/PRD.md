# PRD: 콘서트 티켓 예매 시스템

## 목표
수만 명이 동시에 몰리는 티켓팅 순간의 **재고 동시성·대기열·결제 일관성** 문제를 작게 재현한다. 1000 TPS에서 좌석 중복 예매 0건을 보장하는 백엔드 시스템을 구축한다.

## 사용자
- **예매자**: 로그인 후 좌석을 조회·선점·결제한다.
- **(MVP 제외) 공연 등록자**: 공연/좌석은 초기 seed 데이터로 주입한다.

## 핵심 기능
1. 회원가입/로그인 — JWT access + refresh 토큰 발급, refresh rotation 지원.
2. 공연 목록 및 좌석 조회
3. 좌석 선점 — Redis 10분 TTL. 동일 좌석에 N명 경합 시 1명만 성공.
4. 예약 상태머신 — `PENDING → RESERVED → PAID → CANCELLED`.
5. 대기열 — Redis Sorted Set 기반. 입장·순번·ETA 조회.
6. 결제 확정 API (외부 PG는 mock 처리).
7. k6 부하 테스트 시나리오 + before/after 수치 리포트.

## MVP 제외 사항
- 외부 PG 실제 연동 (결제는 mock 성공/실패 응답)
- 소셜 로그인, 비밀번호 재설정, 이메일 인증 (회원가입은 아이디·비밀번호 입력만)
- 환불 정책, 부분 취소, 좌석 변경
- 관리자 페이지 / 공연 등록 API
- 프론트엔드 (REST API만 제공)

## API 스타일
- REST + JSON.
- 공통 응답 포맷: `{ code, message, data? }`.
- 인증 헤더: `Authorization: Bearer {jwt}`.
- 에러 코드는 `ErrorCode` enum으로 전역 관리.
