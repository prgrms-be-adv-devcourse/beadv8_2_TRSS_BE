# 예외 처리 가이드

팔레트의 모든 실패는 한 길로 처리한다.

```
도메인·서비스에서 throw new DomainException(XxxErrorCode.YYY)
        ↓
GlobalExceptionHandler (@RestControllerAdvice)가 잡는다
        ↓
HTTP {상태} + RsData { "resultCode": "{상태}-{오류 이름}", "msg": "...", "data": ... }
```

컨트롤러에서 try-catch로 실패 응답을 만들지 않는다. 실패는 던지고, 응답으로 바꾸는 일은 핸들러 한 곳에서 한다.

## 1. 꼭 지킬 규칙

1. **실패는 예외로 던진다.** `null`, `false`, `Optional.empty()`로 실패를 돌려주지 않는다. 조회 실패는 `*_NOT_FOUND` 예외다.
2. **`DomainException`은 `ErrorCode`로만 만든다.** 문자열 코드 생성자는 없다. `new DomainException("409-1", "...")`는 컴파일되지 않는다.
3. **오류 코드는 컨텍스트별 enum에 모은다.** `member/domain/MemberErrorCode`처럼 각 컨텍스트의 `domain`에 두고, 다른 컨텍스트의 ErrorCode를 import하지 않는다.
4. **컨트롤러·서비스에서 실패용 `RsData`나 `ResponseEntity.status(...)`를 만들지 않는다.**
5. **잡았으면 처리하거나 다시 던진다.** `catch` 후 로그만 남기고 삼키지 않는다. 외부 예외는 `DomainException(errorCode, cause)`로 감싸 원인을 남긴다.
6. **4xx는 사용자가 고칠 수 있는 실패, 5xx는 우리가 고칠 장애다.** 사용자 잘못을 5xx로, 서버 장애를 4xx로 던지지 않는다.
7. **남의 리소스는 404, 역할·판매자 상태 부족은 403이다.** (5.1)
8. **응답에 내부 정보를 싣지 않는다.** 스택트레이스·SQL·외부 응답 원문은 로그에만 남긴다. 핸들러가 이미 그렇게 동작하므로 `msg`나 `data`에 직접 넣지만 않으면 된다.

## 2. 응답 형식: 성공·실패 모두 `RsData`

```json
// 성공 (200, 생성도 200)
{ "resultCode": "200-1", "msg": "주문이 생성되었습니다.", "data": { "orderId": 123, "status": "CREATED" } }

// 비즈니스 실패 (402)
{ "resultCode": "402-INSUFFICIENT_BALANCE", "msg": "예치금 잔액이 부족합니다.",
  "data": { "shortage": 18000, "recommendedTopupAmount": 20000, "remainingSeconds": 412 } }

// 입력 검증 실패 (400)
{ "resultCode": "400-VALIDATION_FAILED", "msg": "입력값이 올바르지 않습니다.",
  "data": { "fieldErrors": [ { "field": "quantity", "reason": "1~99 사이여야 합니다." } ] } }
```

| 필드 | 성공 | 실패 |
| --- | --- | --- |
| `resultCode` | `200-{순번}`: 조회·생성·처리 모두 `200-1` | `{HTTP 상태}-{오류 이름}`: `409-ORDER_EXPIRED` |
| `msg` | 처리 결과 문장 | 사용자에게 그대로 보여 줄 한국어 문장 |
| `data` | 응답 DTO(엔티티 금지) | 추가 정보가 필요한 오류만(부족액, 필드 오류). 없으면 `null` |

- **성공은 HTTP 200으로 통일한다.** 생성(201)·접수(202)도 구분하지 않는다. 컨트롤러는 `RsData`를 그대로 반환하고 `ResponseEntity`로 감싸지 않는다.
- **실패의 HTTP 상태는 `resultCode` 앞자리에서 정해진다.** `GlobalExceptionHandler`가 `RsData.statusCode()`로 맞춘다.
- 프론트는 실패 분기를 `resultCode`로 한다(`402-INSUFFICIENT_BALANCE`이면 충전 화면으로 이동).
- `docs/palette_proposal.md` API 표의 "주요 오류" `409 EMAIL_DUPLICATED`는 `resultCode` `409-EMAIL_DUPLICATED`를 뜻한다.

```java
@PostMapping("/orders")
public RsData<OrderResponse> create(@Valid @RequestBody CreateOrderRequest req) {
    return RsData.of("200-1", "주문이 생성되었습니다.", createOrderUseCase.create(req));
}
```

- "완료"와 "처리 중"처럼 성공 안에서 구분이 필요하면 HTTP 상태가 아니라 `data.status`로 나눈다(6.4 토스 응답 시간 초과).

## 3. 사용법

### 3.1 컨텍스트별 ErrorCode 만들기

```java
package com.backend.boundedContext.order.domain;

@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)   // 필수: status()·msg()를 만들어 ErrorCode 인터페이스를 구현한다
public enum OrderErrorCode implements ErrorCode {
    ORDER_NOT_FOUND(404, "주문을 찾을 수 없습니다."),
    ORDER_STATE_INVALID(409, "현재 주문 상태에서는 처리할 수 없습니다."),
    ORDER_EXPIRED(409, "결제 가능 시간(15분)이 지난 주문입니다.");

    private final int status;
    private final String msg;
}
```

- `@Accessors(fluent = true)`를 빼면 Lombok이 `getStatus()`를 만들어 `ErrorCode`를 구현하지 못해 컴파일 에러가 난다.
- `name()`은 enum이 이미 갖고 있어 따로 구현하지 않는다. `resultCode()`는 `status + "-" + name`으로 자동 생성된다.
- 상수 이름이 곧 프론트가 분기하는 키다. 한 번 정하면 바꾸지 않는다.
- 새 코드를 추가하면 6장 표에도 함께 적는다.

### 3.2 던지기

```java
// 기본
throw new DomainException(OrderErrorCode.ORDER_STATE_INVALID);

// 실패 응답 data에 추가 정보 싣기
throw new DomainException(PaymentErrorCode.INSUFFICIENT_BALANCE,
        Map.of("shortage", shortage, "recommendedTopupAmount", topup, "remainingSeconds", remaining));

// 외부·하위 예외 감싸기 (원인 stack trace가 ERROR 로그에 남는다)
catch (RestClientException e) {
    throw new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
}
```

| 생성자 | 용도 |
| --- | --- |
| `DomainException(ErrorCode)` | 대부분의 경우 |
| `DomainException(ErrorCode, Object data)` | 프론트에 추가 정보를 줘야 할 때 |
| `DomainException(ErrorCode, Throwable cause)` | 다른 예외를 감쌀 때 |

- 상태 전이 규칙은 엔티티 안에서 던진다.
  ```java
  public void requestReturn(LocalDateTime now) {
      if (status != OrderStatus.PAID) throw new DomainException(OrderErrorCode.ORDER_STATE_INVALID);
      ...
  }
  ```
- 다른 컨텍스트를 호출하다 받은 `DomainException`은 그대로 위로 던진다(주문 생성 중 상품 컨텍스트의 `400-OUT_OF_STOCK`은 그대로 응답).
- 예외 클래스는 `DomainException` 하나로 충분하다. 특정 실패만 골라 잡아야 할 때만 하위 클래스를 만든다(예: 결제의 `InsufficientBalanceException`, 6.2).

## 4. 핸들러가 이미 처리하는 것

아래 예외는 직접 잡을 필요가 없다. `GlobalExceptionHandler`가 공통 코드로 바꿔 응답한다.

| 발생 상황 | 응답 | 비고 |
| --- | --- | --- |
| `DomainException` | 예외에 담긴 코드 그대로 | 4xx는 INFO 한 줄, 5xx는 ERROR + stack trace |
| `@Valid @RequestBody` 검증 실패 | `400-VALIDATION_FAILED` | `data.fieldErrors`에 field·reason (입력값은 민감 정보 노출 방지를 위해 담지 않음) |
| `@RequestParam`·`@PathVariable` 제약 검증 실패 | `400-VALIDATION_FAILED` | `data`는 `null` |
| JSON 파싱 실패, 파라미터 타입 불일치, 필수 파라미터 누락, 지원하지 않는 Content-Type | `400-INVALID_REQUEST` | |
| 필수 헤더 누락 (`@RequestHeader`) | `400-HEADER_REQUIRED` | `data.header`에 헤더 이름 |
| 없는 경로 | `404-RESOURCE_NOT_FOUND` | |
| 지원하지 않는 HTTP 메서드 | `405-METHOD_NOT_ALLOWED` | |
| 낙관적 락 충돌 (`@Version`) | `409-CONCURRENT_UPDATE` | |
| DB UNIQUE·FK 위반 (`DataIntegrityViolationException`) | `409-DATA_CONFLICT` | 원인 SQL은 WARN 로그에만 |
| 그 외 모든 예외 | `500-INTERNAL_ERROR` | ERROR + stack trace, 응답에는 고정 문구만 |

### 4.1 공통 오류 코드 (`GlobalErrorCode`)

핸들러가 자동으로 쓰는 코드 외에, 서비스에서 직접 던질 수 있는 공통 코드다. 컨텍스트에 맞는 코드가 있으면 그쪽을 먼저 쓴다.

| resultCode | 직접 던지는 경우 |
| --- | --- |
| 401-UNAUTHORIZED / 401-AUTH_TOKEN_EXPIRED | Spring Security 추가 후 인증 필터에서 사용 (7장) |
| 403-FORBIDDEN | 역할 부족, 판매자 상태가 APPROVED가 아님 |
| 409-IDEMPOTENCY_KEY_REUSED | 같은 `Idempotency-Key`로 다른 요청 본문이 들어옴 |
| 429-TOO_MANY_REQUESTS | 인증코드 재발송 제한 등 |
| 502-EXTERNAL_SERVICE_ERROR | 토스·메일 서버가 오류로 응답 (`cause`와 함께) |
| 500-INTERNAL_ERROR | 잡은 예외를 감싸 다시 던질 때 (`cause`와 함께) |

## 5. 레이어별 책임

| 레이어 | 하는 일 | 하지 않는 일 |
| --- | --- | --- |
| `in` (컨트롤러, 스케줄러, 리스너) | DTO에 Bean Validation(`@NotBlank`, `@Min` 등) + `@Valid` | try-catch로 실패 `RsData` 만들기 |
| `app` (유스케이스) | 조회 실패 → NOT_FOUND, 소유권·권한 검사, 중복 사전 검사 | HTTP 상태 다루기 |
| `domain` (엔티티) | 상태 전이·불변식 위반 → `*_STATE_INVALID` 등 | Repository·외부 호출 |
| `out` (Repository, 외부 어댑터) | 외부 라이브러리 예외를 `DomainException`으로 감싸기 | 비즈니스 판단 |

- **형식 검증**(빈 값, 범위, 이메일 형식)은 `in`에서 Bean Validation으로 끝낸다.
- **비즈니스 검증**(재고, 상태, 소유권, 잔액)은 `app`·`domain`에서 한다.

### 5.1 소유권은 404, 역할·상태는 403

| 상황 | 응답 |
| --- | --- |
| 남의 주문·충전·정산서·상품을 ID로 조회·수정 | `404-*_NOT_FOUND`: 리소스 존재 여부를 숨긴다(ID가 자동 증가라 찔러 보기 방지) |
| 역할 부족 (USER가 `/admin/**`, `/seller/**` 호출) | `403-FORBIDDEN` |
| 판매자 상태가 APPROVED가 아님 | `403-FORBIDDEN`: 본인 상태 문제라 이유를 알려 준다 |

소유권은 따로 조회한 뒤 비교하지 말고, **조회 조건에 소유자를 넣는다.**

```java
Order order = orderRepository.findByIdAndBuyerId(orderId, memberId)
        .orElseThrow(() -> new DomainException(OrderErrorCode.ORDER_NOT_FOUND));
```

주문·반품은 `buyerId`, 충전·지갑·원장은 `memberId`, 판매자 상품·정산서는 `sellerId`를 조건에 넣는다. 관리자(`/admin/**`)는 `id`로만 조회한다.

## 6. 주의할 상황

### 6.1 중복(UNIQUE) 검사

`app`에서 먼저 조회해 의미 있는 코드(`409-EMAIL_DUPLICATED` 등)로 던진다. `409-DATA_CONFLICT`는 동시 요청이 DB 제약에 걸렸을 때의 **안전망**일 뿐이다. 판매자 활성 신청, 주문당 성공 결제처럼 중요한 곳은 `DataIntegrityViolationException`을 잡아 해당 컨텍스트 코드로 바꾼다.

### 6.2 롤백되면 안 되는 실패 기록

예외가 나면 트랜잭션이 롤백되므로, 같은 트랜잭션 안에서 저장한 실패 기록도 사라진다. 실패 상태를 남겨야 하면(예: 결제 `FAILED_INSUFFICIENT_BALANCE`) **별도 빈의 `@Transactional(propagation = REQUIRES_NEW)` 메서드**로 기록한 뒤 예외를 다시 던진다.

```java
public PayResult pay(PayCommand cmd) {                // 트랜잭션 없음
    try {
        return paymentProcessor.payInTx(cmd);         // 다른 빈의 @Transactional
    } catch (InsufficientBalanceException e) {
        failureRecorder.record(cmd, FAILED_INSUFFICIENT_BALANCE);   // REQUIRES_NEW
        throw e;
    }
}
```

같은 클래스 안에서 `@Transactional` 메서드를 호출하면 프록시를 거치지 않아 트랜잭션이 적용되지 않는다. 반드시 다른 빈으로 나눈다.

### 6.3 동시성 경합

상태 변경은 조건부 UPDATE로 하고, 바뀐 행이 0이면 예외로 바꾼다.

```java
int updated = orderRepository.markPaid(orderId, now);  // WHERE status IN (...) AND expires_at > :now
if (updated == 0) throw new DomainException(OrderErrorCode.ORDER_STATE_INVALID);
```

### 6.4 외부 연동 (토스·메일)

- 외부 호출은 DB 트랜잭션 밖에서 하고, HTTP 클라이언트 예외가 `out` 어댑터 밖으로 새지 않게 `DomainException`으로 감싼다.
- 외부가 오류로 응답하면 `502-EXTERNAL_SERVICE_ERROR`(또는 컨텍스트 코드)로 던진다.
- **응답 시간 초과는 실패로 단정하지 않는다.** 카드 승인은 이미 됐을 수 있다. 충전을 `PROCESSING`으로 두고 성공 응답 `200-1` + `data.status: "PROCESSING"`(결과 확인 중)으로 돌려준다. 프론트는 `data.status`로 완료와 확인 중을 구분한다. 대사 스케줄러가 토스 조회로 결과를 확정한다.
- 같은 `Idempotency-Key` + 같은 요청은 오류가 아니라 처음 응답을 그대로 돌려준다. 다른 본문이면 `409-IDEMPOTENCY_KEY_REUSED`다.

### 6.5 스케줄러·Spring Batch·이벤트 리스너

HTTP 요청이 아니므로 `GlobalExceptionHandler`가 동작하지 않는다.

- **건별로 잡는다.** 한 건의 실패가 전체 작업을 멈추지 않게 건마다 try-catch하고, 실패 건은 로그를 남긴 뒤 다음 실행에서 다시 처리한다.
- **실패 사유를 저장한다.** 정산서 `last_fail_reason`, 환불 `fail_reason`, Outbox `retry_count` 등에 남겨 관리자 화면에서 보이게 한다.
- **재시도할 실패와 아닌 실패를 나눈다.** 일시 장애(타임아웃, 락 경합)는 재시도하고, 데이터 문제(금액 불일치 등)는 바로 `FAILED`·`MANUAL_REQUIRED`로 둔다.

## 7. Spring Security를 추가할 때

인증 실패(401)·권한 부족(403)은 필터 단계에서 발생해 `GlobalExceptionHandler`에 도달하지 않는다. 다음 두 개를 구현해 같은 `RsData` 형식으로 응답을 직접 쓴다.

- `AuthenticationEntryPoint` → `401-UNAUTHORIZED` / `401-AUTH_TOKEN_EXPIRED`
- `AccessDeniedHandler` → `403-FORBIDDEN`

`@PreAuthorize`를 쓴다면 컨트롤러 단에서 나는 `AccessDeniedException`이 `Exception` 핸들러에 잡혀 500이 되지 않도록 `GlobalExceptionHandler`에도 403 핸들러를 추가한다.

## 8. 컨텍스트별 오류 코드

`docs/palette_proposal.md` 3장 API 명세의 오류를 enum으로 옮긴다. 담당자가 기능을 만들며 추가하고, 추가하면 이 표도 함께 고친다.

| 컨텍스트(담당) | resultCode | 언제 |
| --- | --- | --- |
| member (시연·지은) | 404-MEMBER_NOT_FOUND | 회원 없음 (`MemberApi.getMember`) |
| | 409-EMAIL_DUPLICATED | 가입된 이메일 |
| | 400-CODE_INVALID / 400-CODE_EXPIRED | 인증코드 불일치 / 5분 경과 |
| | 400-CODE_ATTEMPTS_EXCEEDED | 인증코드 5회 불일치 (코드 삭제, 재발송 필요) |
| | 400-EMAIL_NOT_VERIFIED | 인증 없이 가입 시도 |
| | 401-LOGIN_FAILED | 이메일·비밀번호 불일치(어느 쪽인지 알려 주지 않음) |
| | 409-WITHDRAWAL_NOT_ALLOWED | 진행 중 주문·미정산·잔액이 남음 (`data.reasons`) |
| | 409-SELLER_DUPLICATED | 회원 또는 사업자번호로 활성 신청이 이미 있음 |
| | 409-SELLER_STATE_INVALID | 승인·반려·철회를 할 수 없는 상태 |
| | 409-UNSETTLED_AMOUNT_EXISTS | 미정산 금액이 있어 철회 요청 불가 |
| product (유진) | 404-PRODUCT_NOT_FOUND | |
| | 409-PRODUCT_NOT_ON_SALE | STOPPED·DELETED·SOLD_OUT 상품 담기·주문 |
| | 409-PRODUCT_NOT_EDITABLE | 삭제·중지된 상품 수정 |
| | 400-STOCK_NEGATIVE | 재고 조정 결과가 음수 |
| | 400-OUT_OF_STOCK | 주문 수량 > 재고 |
| | 400-STOCK_EXCEEDED | 장바구니 수량 > 재고 |
| | 409-STOP_REQUEST_PENDING | 심사 대기 중지 요청이 이미 있음 |
| | 409-CATEGORY_DUPLICATED | 같은 부모 아래 이름 중복 |
| | 409-PRODUCT_DELETED | 반품 승인 시 삭제된 상품의 재고 복원 선택 |
| order (은정) | 400-SELF_PURCHASE_NOT_ALLOWED | 본인 상품 담기·주문 |
| | 409-PRICE_CHANGED | 예상 금액 ≠ 현재 금액 (`data.currentAmount`) |
| | 409-CART_ITEM_IN_PENDING_ORDER | 결제 대기 주문에 포함된 장바구니 항목 |
| | 404-ORDER_NOT_FOUND | |
| | 409-ORDER_STATE_INVALID | 상태 전이 불가(취소·확정·반품 등) |
| | 409-ORDER_EXPIRED | 결제 시점에 15분 경과 |
| | 409-RETURN_ALREADY_REQUESTED | 반품 1회 사용 |
| payment (지은·시연) | 402-INSUFFICIENT_BALANCE | 잔액 부족 (`data.shortage`·`recommendedTopupAmount`·`remainingSeconds`) |
| | 400-TOPUP_AMOUNT_INVALID | 1만 원 단위·1만~100만 원 위반 |
| | 400-AMOUNT_MISMATCH | 저장 금액 ≠ 승인 요청 금액 |
| | 409-TOPUP_ALREADY_PROCESSED | 이미 승인된 충전·같은 결제키 |
| | 409-TOPUP_ALREADY_USED | 남은 금액 ≠ 충전 금액이라 취소 불가 |
| | 404-TOPUP_NOT_FOUND / 404-WALLET_NOT_FOUND | |
| settlement (다은) | 404-SETTLEMENT_NOT_FOUND | |
| | 409-SETTLEMENT_RUNNING | 같은 월 정산 실행 중 |
| | 409-SETTLEMENT_STATE_INVALID | 보류·해제·재처리를 할 수 없는 상태 |
| | 400-SETTLEMENT_MONTH_INVALID | 끝나지 않은 달 정산 시도 |

## 9. 로그 규칙

`GlobalExceptionHandler`의 현재 동작:

| 구분 | 레벨 | 스택트레이스 |
| --- | --- | --- |
| `DomainException` 4xx | INFO | 없음 |
| `DomainException` 5xx | ERROR | 있음 (`cause` 포함) |
| DB 제약 위반 | WARN | 없음 (원인 메시지만) |
| 처리되지 않은 예외 | ERROR | 있음 |

- 직접 로그를 남길 때도 같은 기준을 따른다. 4xx를 ERROR로 남기지 않는다(운영 알림이 울린다).
- 이메일, 휴대폰, 계좌번호, 비밀번호, 토큰은 로그에 원문으로 남기지 않는다(계좌는 끝 4자리만).
- `application.yaml`의 `show-sql: true`는 로컬 전용이다. 운영 프로필에서는 끈다.

## 10. Swagger 문서화

springdoc은 예외로 나가는 상태를 추론하지 못한다. 성공 상태와 주요 실패 응답을 `@ApiResponse`로 적는다.

```java
@Operation(summary = "주문 결제")
@ApiResponse(responseCode = "200", description = "200-1 결제 완료")
@ApiResponse(responseCode = "402", description = "402-INSUFFICIENT_BALANCE 잔액 부족(data: 부족액·추천 충전액·남은 시간)")
@ApiResponse(responseCode = "409", description = "409-ORDER_EXPIRED / 409-ORDER_STATE_INVALID")
@PostMapping("/orders/{orderId}/payment")
public RsData<PayResponse> pay(...) { ... }
```

## 11. 테스트할 때 확인할 것

- [ ] 실패 응답의 HTTP 상태와 `resultCode`를 **함께** 검증한다
- [ ] `@Valid` 실패 시 `data.fieldErrors`가 채워진다
- [ ] 다른 회원의 리소스 ID로 요청하면 403이 아니라 404가 온다
- [ ] 롤백되면 안 되는 실패 기록(6.2)이 예외 후에도 DB에 남는다
- [ ] 동시 요청 시 한쪽만 성공하고 다른 쪽은 409가 온다
- [ ] 예상 못 한 예외의 응답에 스택트레이스·SQL이 없다

## 12. 구현 현황

| 항목 | 상태 |
| --- | --- |
| `RsData`, `ErrorCode`, `GlobalErrorCode`, `DomainException`, `GlobalExceptionHandler` | 완료 |
| 실패 응답을 `RsData`로 통일, 405·415 처리 | 완료 |
| 성공 응답 HTTP 200 통일 | 완료 (별도 코드 없음) |
| Security `AuthenticationEntryPoint`·`AccessDeniedHandler` | Security 도입 시 (7장) |
| 요청별 `traceId`(MDC, `X-Trace-Id` 헤더) | 미구현 |
| 운영 프로필에서 `show-sql` 끄기 | 미구현 |
