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

- **성공은 HTTP 200으로 통일한다.** 생성(201)·접수(202)도 구분하지 않는다. (예외: 판매자 신청 `POST /sellers`는 판매자 데이터를 새로 만드므로 HTTP 201·`201-1`로 응답한다) 컨트롤러는 `RsData`를 그대로 반환하고 `ResponseEntity`로 감싸지 않는다.
- **실패의 HTTP 상태는 `resultCode` 앞자리에서 정해진다.** `GlobalExceptionHandler`가 `RsData.statusCode()`로 맞춘다.
- 프론트는 실패 분기를 `resultCode`로 한다(`402-INSUFFICIENT_BALANCE`이면 충전 화면으로 이동).
- `docs/palette_proposal.md` API 표의 "주요 오류" `409 EMAIL_DUPLICATED`는 `resultCode` `409-EMAIL_DUPLICATED`를 뜻한다.

```java
@PostMapping("/orders")
public RsData<OrderResponse> create(@Valid @RequestBody CreateOrderRequest req) {
    return RsData.of("200-1", "주문이 생성되었습니다.", createOrderUseCase.create(req));
}
```

- "완료"와 "처리 중"처럼 성공 안에서 구분이 필요하면 HTTP 상태가 아니라 `data.status`로 나눈다(6.4 PG 응답 시간 초과).

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
| 401-UNAUTHORIZED / 401-AUTH_TOKEN_EXPIRED | 인증 필터·`AuthenticationEntryPoint`에서 사용 (7장) |
| 403-FORBIDDEN | 역할 부족(URL 규칙). 판매자 상태가 APPROVED가 아니면 member의 `403-SELLER_NOT_APPROVED`를 쓴다 |
| 409-IDEMPOTENCY_KEY_REUSED | 결제 멱등 키(`PayCommand.idempotencyKey`)가 다른 주문·구매자·금액에 다시 쓰임 (`Idempotency-Key` 헤더는 미구현) |
| 429-TOO_MANY_REQUESTS | 인증코드 재발송 제한 등 |
| 502-EXTERNAL_SERVICE_ERROR | 토스·헥토·카카오·메일 서버가 오류로 응답하거나 응답이 없음 (`cause`와 함께) |
| 503-INTERNAL_API_AUTH_FAILED | 내부 API 호출이 상대 서비스의 내부 키 검사(401·403)에서 막힘. 설정 오류라 ERROR 로그를 남긴다 (지금은 market `PaymentApiClient`) |
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
| 역할 부족 (USER가 `/admin/**`, ADMIN이 `/seller/**`·구매자 API 호출) | `403-FORBIDDEN` |
| 판매자 상태가 APPROVED가 아님 (판매자 신청 전·심사 중·철회·제재 상태에서 판매 기능 호출) | `403-SELLER_NOT_APPROVED`: 본인 상태 문제라 이유를 알려 준다 |

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

예외가 나면 트랜잭션이 롤백되므로, 같은 트랜잭션 안에서 저장한 실패 기록도 사라진다. 실패 상태를 남겨야 하면(예: 결제 `FAILED_INSUFFICIENT_BALANCE`) **별도 빈의 `@Transactional(propagation = REQUIRES_NEW)` 메서드**로 기록한다.

결제는 잔액 부족을 예외가 아니라 결과로 돌려준다. 결제 모듈이 실패를 별도 트랜잭션으로 기록하고 FAILED 결과를 반환하면, 주문 모듈은 결과 반영 트랜잭션에서 주문을 PAYMENT_FAILED로 바꿔 커밋한 뒤 `402-INSUFFICIENT_BALANCE`를 던진다. 결제는 HTTP라 주문 트랜잭션에 참여하지 않는다(주문 PAYING 커밋 → 결제 요청 → 결과 반영, `palette_shared_contract.md` 9.1).

```java
// PaymentPayUseCase (결제 모듈)
if (wallet.getBalance() < command.amount()) {
    Payment failed = paymentFailureRecorder.record(command, wallet.getId(),
            FAILED_INSUFFICIENT_BALANCE, reason);      // 다른 빈, REQUIRES_NEW
    return toResult(failed, wallet);                   // 예외 없이 결과 반환
}
```

같은 클래스 안에서 `@Transactional` 메서드를 호출하면 프록시를 거치지 않아 트랜잭션이 적용되지 않는다. 반드시 다른 빈으로 나눈다.

### 6.3 동시성 경합

상태 변경은 조건부 UPDATE로 하고 바뀐 행이 0이면 예외로 바꾸거나, 행을 비관적 잠금(`findBy…ForUpdate`)으로 조회한 뒤 엔티티에서 상태를 확인한다. 충전 READY 만료는 조건부 UPDATE를 쓴다.

```java
int updated = orderRepository.markPaid(orderId, now);  // WHERE status IN (...) AND expires_at > :now
if (updated == 0) throw new DomainException(OrderErrorCode.ORDER_STATE_INVALID);
```

### 6.4 외부 연동 (토스·헥토·메일·OpenAI)

- 외부 호출은 DB 트랜잭션 밖에서 하고, HTTP 클라이언트 예외가 `out` 어댑터 밖으로 새지 않게 `DomainException`으로 감싼다.
- 외부가 오류로 응답하면 `502-EXTERNAL_SERVICE_ERROR`(또는 컨텍스트 코드)로 던진다.
- 토스 충전 승인의 오류 처리:
  - 401·`INVALID_API_KEY`·`UNAUTHORIZED_KEY`(키 오류) → 충전 FAILED_SYSTEM_ERROR, `502-EXTERNAL_SERVICE_ERROR`
  - 그 밖의 4xx(카드 거절 등) → 충전 FAILED_PG_DECLINED, `400-TOPUP_PAYMENT_DECLINED`(`data.code`·`message`)
  - 5xx·`ALREADY_PROCESSED_PAYMENT`(결과 모름) → 바로 조회해 승인됐으면 반영, 아니면 충전 PROCESSING, `200-1` + `data.status: "PROCESSING"` (아래 "승인 결과를 모를 때")
  - 승인 후 DB 반영 실패 → 충전 PROCESSING, `200-1` + `data.status: "PROCESSING"`
  - 승인됐지만 허용하지 않은 결제 수단(카드·간편결제·계좌이체 외) → 예치금에 반영하지 않고 토스 전액 취소, `400-TOPUP_METHOD_NOT_ALLOWED`. 취소 결과를 모르거나 실패하면 충전 METHOD_CANCELING으로 두고 대사 스케줄러가 다시 취소(`TOPUP-{id}-METHOD-CANCEL-{회차}`, 첫 시도 포함 최대 3회. 3회 모두 실패하면 다음 회차에 한 번 더 조회한 뒤 수동 확인으로 표시)
  - 연결 5초·응답 30초 제한 (`custom.toss`). 결제 조회만 응답 5초(`lookupReadTimeoutSeconds`): 끊겨도 다음 회차에 다시 조회하면 되므로 짧게 둬 장애 때 대사가 오래 붙잡히지 않게 한다. 승인·취소는 끊으면 결과 모름이 늘어나 30초 그대로
- 토스 충전 취소의 오류 처리 (취소 시작 때 잔액을 먼저 빼고, 확정 실패면 되돌린다):
  - 401·`INVALID_API_KEY`·`UNAUTHORIZED_KEY`(키 오류) → 잔액·남은 금액 되돌림(원장 `CHARGE_CANCEL_RESTORE`), `502-EXTERNAL_SERVICE_ERROR`
  - 그 밖의 4xx(환불 기간 지남·부분 취소 불가 수단 등) → 거절. 아래 "거절이어도 조회로 확인한 뒤 되돌린다"에 따라 처리
  - 응답 없음·5xx·`ALREADY_CANCELED_PAYMENT`·`ALREADY_REFUND_PAYMENT`·`NOT_CANCELABLE_AMOUNT`(일부가 이미 취소돼 남은 금액보다 커졌을 수 있음)·`IDEMPOTENT_REQUEST_PROCESSING`·`ALREADY_PROCESSING_REQUEST`·`FORBIDDEN_CONSECUTIVE_REQUEST`·`PROVIDER_ERROR`·취소 후 DB 반영 실패 → 취소됐을 수 있으므로 되돌리지 않고 충전 CANCELING, `200-1` + `data.status: "CANCELING"`. 대사 스케줄러가 토스 결제키 조회의 취소 금액 합계로 확정하거나 되돌린다. 합계가 어느 쪽과도 맞지 않거나 결제가 없으면 수동 확인으로 표시한다
  - 토스 `Idempotency-Key`는 취소 회차마다 다르게(`TOPUP-{id}-CANCEL-{회차}`) 보내 재시도해도 같은 취소가 두 번 처리되지 않게 한다
- **충전 취소는 PG가 거절해도 조회로 확인한 뒤 되돌린다(토스·헥토 공통).** 상점관리자에서 직접 취소한 결제는 거절 코드로 돌아올 수 있어, 바로 되돌리면 카드 환불과 예치금이 둘 다 남는 이중 환불이 될 수 있어서다
  - 조회한 취소 합계 = 이번 취소 전 금액 → 이번 취소가 반영되지 않았으므로 되돌림, `400-TOPUP_CANCEL_DECLINED`(`data.code`·`message`)
  - 그 외·조회 결과 모름·PG에 결제 없음 → 되돌리지 않고 충전 CANCELING, `200-1` + `data.status: "CANCELING"`(ERROR 로그). CANCELING 대사가 확정·되돌림·수동 확인으로 정리한다
  - 키 오류는 PG가 인증 단계에서 거절한 것이라 조회 없이 바로 되돌린다
- 대사 스케줄러의 토스 조회는 결제키(`GET /v1/payments/{paymentKey}`)로 한다. 주문번호 조회(`/v1/payments/orders/{orderId}`)는 결제위젯 시크릿 키로 호출할 수 없다
- 헥토(신용카드, 인증 분리 승인)는 성공·실패 모두 HTTP 200에 `outStatCd`(`0021` 성공, `0031` 실패)와 `outRsltCd`로 알려 준다. 어댑터가 이 코드를 토스와 같은 PG 중립 결과·예외로 바꾸므로 충전 로직의 처리(충전 상태·응답 코드)는 토스와 같다
  - 공통: 응답 없음·HTTP 오류·해석할 수 없는 응답 → 결과 모름. 암호화 키가 없어 요청을 만들지 못함 → 키 오류(`502-EXTERNAL_SERVICE_ERROR`, 헥토에 요청이 가지 않음)
  - 충전 준비: 결제창 요청 값(금액 암호화·해시)을 저장 전에 만든다. 키 오류면 충전 건을 만들지 않고 `502-EXTERNAL_SERVICE_ERROR`
  - 승인: `0031` → 충전 FAILED_PG_DECLINED, `400-TOPUP_PAYMENT_DECLINED`(`data.code`: 헥토 `outRsltCd`). 결과 모름·**승인 응답 해시 불일치** → 바로 조회 후 재승인(아래 "승인 결과를 모를 때"), 승인 후 DB 반영 실패 → 충전 PROCESSING
  - 승인 `ST50`(중복결제)은 `0031`이지만 **거절이 아니라 결과 모름**이다. 같은 인증 거래번호로 두 번째 승인을 보내면 헥토가 `ST50`으로 거절하는데(테스트베드 확인, 며칠 지나면 `ST06`), 앞선 승인이 처리돼 카드는 결제됐다는 뜻이다. 거절로 기록하면 돈은 빠지고 충전은 실패로 끝나므로 PROCESSING으로 두고 대사가 조회로 반영한다
  - 취소: `ST25`(이미 취소 완료)·`ST57`(취소 금액 초과)·결과 모름 → 앞선 요청이나 상점관리자에서 취소됐을 수 있어 되돌리지 않고 충전 CANCELING. 그 밖의 `0031` → 거절(조회로 확인한 뒤 되돌림, 위 참고)
  - **헥토 취소는 멱등 키가 없다.** 취소 회차(`cnclOrd`, 충전 건의 취소 회차)를 보내지만, 이미 성공한 회차를 다시 보내면 또 취소된다(테스트베드 확인). 그래서 결과를 모르는 취소는 다시 요청하지 않고, 대사 스케줄러가 조회한 취소 금액 합계로 확정하거나 되돌린다(토스와 같은 방식)
  - 조회: 주문번호·원거래 일자(충전 건의 결제 요청 시각)·금액으로 한다. `0000` 정상(취소 금액이 있으면 부분 취소) / `0001` 취소 / `0009` 결제 실패, 그 외 결과 코드는 확정되지 않음으로 두고 다음 회차에 다시 확인한다. 실패 `ST06`(거래 없음)은 결제창에서 인증하지 않은 주문이라 "PG에 결제 없음"과 같게 처리하고(PROCESSING이면 FAILED_SYSTEM_ERROR, CANCELING·METHOD_CANCELING이면 수동 확인으로 표시), 그 밖의 실패는 결과 모름으로 둔다
  - 조회의 원거래 일자: `ST06`이고 승인 요청 시각(`confirm_requested_at`, PROCESSING 선점 때 저장)의 날짜가 결제 요청 날짜와 다르면(자정을 넘긴 충전 건) 그 날짜로 한 번 더 조회한 뒤에 거래 없음으로 본다. 테스트베드는 원거래 일자 ±1일까지 찾아 주지만 문서에 없는 동작이라 기대지 않는다
  - 결제창 결과(`nextUrl`·`cancUrl`)는 브라우저를 거쳐 와서 믿지 않는다. 서버는 프론트 충전 결과 화면으로 넘기기만 하고, 승인 API가 회원·주문번호·금액을 확인한 뒤 헥토 승인 응답으로 확정한다. 결과 알림(`notiUrl`)은 기록만 하고 `OK`로 응답한다
- 충전 승인 결과를 모를 때 (토스·헥토 공통): 승인 응답을 못 받으면 PROCESSING으로 미루기 전에 그 자리에서 PG에 조회한다. 사용자가 결제창 인증까지 마치고 몇 분 뒤 실패를 받지 않게 하기 위함이다
  - 조회가 승인됨 → 그 결과로 반영(DONE)
  - 거래 없음(처리되지 않음)이고 두 번째 승인을 거절하는 PG(헥토) → 승인을 **한 번만** 다시 보낸다. 성공하면 반영, 거절이면 FAILED_PG_DECLINED, 다시 결과 모름(`ST50` 포함)이면 PROCESSING. 토스는 다시 승인해도 `ALREADY_PROCESSED_PAYMENT`(결과 모름)라 재승인하지 않는다(`TopupGatewayPort.canRetryConfirm`)
  - 그 밖(진행 중, 조회도 결과 모름, 조회만 설정 오류) → PROCESSING, 대사가 정리
  - 대사는 인증만 된 건을 다시 승인하지 않는다(PROCESSING 대사의 `ST06`은 FAILED_SYSTEM_ERROR). 재승인은 승인 요청 직후, 처리되지 않은 것을 조회로 확인한 경우에만 한 번 한다
- 충전 PG 서킷 브레이커와 PG 자동 선택: PG별(토스·헥토) 서킷 브레이커(Resilience4j)가 승인·조회·취소 결과를 센다(`resilience4j.circuitbreaker.instances.pg-{PG}`)
  - 실패로 세는 것은 결과 모름(응답 없음·연결 끊김·5xx)뿐이다. 카드 거절·거래 없음은 PG가 응답한 것이라 성공, 설정 오류는 세지 않는다
  - 최근 10회 중 실패율 50% 이상(최소 5회)이면 60초 동안 사용 불가(OPEN, ERROR 로그), 그 뒤 저절로 HALF_OPEN이 되고 3회 결과로 다시 정한다. 헥토 테스트베드는 평소 연결의 약 1/3을 끊어서 기준을 그보다 높게 뒀다. 상태·실패율은 `resilience4j.circuitbreaker.*` 메트릭으로 Prometheus에 나간다
  - 호출을 막지 않고 결과만 센다. 승인은 사용자가 이미 결제창 인증을 마쳤고, 대사·취소는 돈이 걸린 정리 작업이라 장애 중에도 계속한다. 장애 중 대사 호출은 조회 응답 5초와 결과 모름 연속 3건이면 회차 중단(아래 "충전 대사는 밀린 건을 한 번에 처리한다")으로 줄인다
  - 충전 준비는 사용자가 PG를 고르지 않는다. 토스가 기본이고, 토스가 사용 불가(OPEN·FORCED_OPEN)면 헥토, 둘 다 사용 불가면 `503-TOPUP_PG_UNAVAILABLE`(충전 건을 만들지 않음). 프론트는 응답의 `pgProvider`로 결제창을 고른다
  - 연결 5초·응답 30초 제한 (`custom.hecto`). 결제 조회만 응답 5초(`lookupReadTimeoutSeconds`, 토스와 같은 이유)
- 챗봇(ai-service)의 OpenAI 오류 처리. 챗봇은 답을 못 주는 것보다 FAQ 원문이라도 주는 쪽을 택한다:
  - 검색어 다시 쓰기(채팅) 실패·시간 초과·빈 답 → 예외로 응답하지 않고 원래 질문(같은 대화의 최근 질문 + 이번 질문)으로 FAQ를 찾는다. WARN 로그만 남긴다
  - 질문 임베딩 실패 → FAQ를 찾을 수 없으므로 `502-EXTERNAL_SERVICE_ERROR`(질문·답변을 대화에 남기지 않음)
  - 답변 생성(채팅) 실패·시간 초과(10초, `spring.ai.openai.chat.timeout`)·빈 답변 → 예외로 응답하지 않고 가장 비슷한 FAQ 답변을 그대로 `200-1` + `data.answerType: "FAQ"`로 준다. WARN 로그만 남긴다
  - OpenAI SDK 재시도는 끈다(`max-retries: 0`). 재시도로 응답이 몇십 초씩 늦어지지 않고 바로 FAQ 답변으로 넘어가게 하기 위함이다
  - 근거 FAQ가 없으면 OpenAI 채팅을 부르지 않고 고객센터 안내 문구(`answerType: "NO_MATCH"`)로 답한다. 근거 없이 지어낸 답을 막기 위함이다
  - 답변 끝의 근거 표시(`[근거: 1, 3]`·`[근거: 없음]`)를 떼어 `sources`를 정한다. `[근거: 없음]`이면 `NO_MATCH` + 빈 `sources`. 표시가 없거나 읽을 수 없으면 예외로 응답하지 않고 찾은 FAQ를 모두 근거로 주며 WARN 로그만 남긴다
  - 요청 수 제한(Redis)이 실패하면 챗봇을 막지 않고 WARN만 남긴다. 제한은 비용 보호용이라 Redis 장애로 상담까지 막지 않는다
- **응답 시간 초과는 실패로 단정하지 않는다.** 카드 승인은 이미 됐을 수 있다. 충전을 `PROCESSING`으로 두고 성공 응답 `200-1` + `data.status: "PROCESSING"`(결과 확인 중)으로 돌려준다. 프론트는 `data.status`로 완료와 확인 중을 구분한다. 그 전에 그 자리에서 PG에 한 번 조회하고(위 "충전 승인 결과를 모를 때"), 그래도 모르면 대사 스케줄러가 PG 조회로 결과를 확정한다.
- 같은 `Idempotency-Key` + 같은 요청은 오류가 아니라 처음 응답을 그대로 돌려준다. 다른 본문이면 `409-IDEMPOTENCY_KEY_REUSED`다. (주문 생성 `POST /orders`는 구현: 같은 구매자·같은 키면 처음 만든 주문을 돌려주고(그 주문이 취소됐으면 `409-IDEMPOTENCY_KEY_EXPIRED`), 본문은 비교하지 않는다. 그 밖의 API는 헤더 미구현. 충전 승인 `POST /topups/confirm`은 헤더 대신 주문번호(`orderId`)를 키로 쓴다: 같은 회원·주문번호에 결제키·금액이 같은 재요청이면 PG를 다시 부르지 않고 `DONE`(잔액은 지금 지갑 잔액)·`PROCESSING`을 `200-1`로 돌려주고, 결제키·금액이 다르거나 실패·만료·취소된 충전 건이면 `409-TOPUP_ALREADY_PROCESSED`(상태는 `GET /topups/{id}`로 확인). 내부 `PaymentApi.pay()`는 본문 `idempotencyKey`에 처음 결과를 돌려준다)

### 6.5 스케줄러·Spring Batch·이벤트 리스너

HTTP 요청이 아니므로 `GlobalExceptionHandler`가 동작하지 않는다.

- **건별로 잡는다.** 한 건의 실패가 전체 작업을 멈추지 않게 건마다 try-catch하고, 실패 건은 로그를 남긴 뒤 다음 실행에서 다시 처리한다.
- **실패 사유를 저장한다.** 정산서 `last_fail_reason`, 충전·결제 `fail_reason`(환불 `fail_reason`은 환불 구현 때) 등에 남겨 관리자 화면에서 보이게 한다.
- **재시도할 실패와 아닌 실패를 나눈다.** 일시 장애(타임아웃, 락 경합)는 재시도하고, 데이터 문제(금액 불일치 등)는 바로 `FAILED`·`MANUAL_REQUIRED`로 둔다.
- **충전 대사는 밀린 건을 한 번에 처리한다.** PROCESSING·CANCELING·METHOD_CANCELING 대사는 `topupReconcileBatchSize`(100)건씩 가져오고, 배치를 다 채우면 같은 실행에서 마지막 건 다음부터 이어서 가져온다(수정 시각·ID 순). PG 조회 결과를 모르는 건(응답 없음 등)이 나오면 PG 장애일 수 있어 그 배치까지만 처리하고, 결과 모름이 연속 `topupReconcileUnknownStreak`(3)건이면 배치 중간이라도 바로 멈춘다(남은 건은 다음 회차). 다른 결과가 나오면 연속 수는 0으로 돌아가고, 예상 못 한 오류(DB 등)는 세지 않는다. 그래서 PG가 응답 없이 멈춰도 한 회차는 약 15초(3건 × 조회 응답 5초)로 끝난다. 장애 중 한 번 실행이 길어져 같은 스케줄러 스레드를 쓰는 다른 작업이 밀리지 않게 하기 위함이다.
- **임베딩 스케줄러(ai)는 실패 건을 비워 두고 다시 시도한다.** OpenAI는 트랜잭션 밖에서 부르고, 임베딩하는 동안 글이 바뀌었으면(해시가 다르면) 저장하지 않는다.
  - 추천 상품·설문: 묶음 호출이 실패하면 한 건씩 다시 보내 실패한 건만 가려내고, 실패한 건은 1·5·30·60분 뒤로 다시 시도를 미룬다(`custom.recommend.embeddingRetryDelayMinutes`, 그 뒤로는 60분마다). 한 건 때문에 다른 건이 막히지 않는다. 같은 건이 3번 연속 실패하면 ERROR 로그를 한 번 남긴다(`embeddingFailureAlertAttempts`, Slack 알림). 한 건씩 보내다 3건이 연달아 실패하면 OpenAI 장애로 보고 나머지는 보내지 않는다(나머지도 실패로 남겨 같은 재시도 간격을 탄다)
  - FAQ: 묶음 호출이 실패하면 WARN만 남기고 임베딩을 비워 두며, 다음 실행(5초 뒤)에 같은 묶음을 다시 시도한다. 한 건씩 나눠 보내거나 시도를 미루지 않는다(관리자가 등록하는 FAQ 수십 건이라 따로 두지 않음)
- **오래 남은 충전 건은 알린다.** 결과가 정해지지 않은 상태(PROCESSING·CANCELING·METHOD_CANCELING)로 `topupStuckWarnMinutes`(60분)가 넘게 남은 충전 건은 대사 간격마다 상태별로 ERROR 로그 한 줄(건수·가장 작은 충전 건 ID)을 남긴다. Grafana가 같은 알림을 1시간마다 다시 보내므로, 남아 있는 동안 1시간마다 알리고 정리되면 멈춘다. 이런 건이 있으면 회원 탈퇴가 막힌다(`PENDING_TOPUPS`). 수동 확인으로 표시된 건은 표시할 때 알렸으므로 세지 않는다.
- **자동으로 정할 수 없는 충전 취소 건은 수동 확인으로 표시하고 관리자가 정리한다.** 자동으로 확정하거나 되돌리면 이중 환불이나 미환불이 될 수 있어서다.
  - 대상: CANCELING에서 PG 취소 합계가 이전 취소와도, 이전 + 이번 취소와도 다름(`CANCEL_AMOUNT_MISMATCH`, 상점관리자 직접 취소 등)·PG에 결제 없음(`PG_PAYMENT_NOT_FOUND`), METHOD_CANCELING에서 일부만 취소됨(`METHOD_CANCEL_PARTIAL`)·PG에 결제 없음·전액 취소 3회 실패(`METHOD_CANCEL_RETRY_EXHAUSTED`)
  - 표시(`topup.manual_check_reason`·`manual_check_at`)할 때 ERROR 로그를 한 번 남기고, 표시된 건은 대사·오래 남은 건 경고에서 빠진다. 상태와 금액은 그대로라 정리 전까지 탈퇴는 막힌다
  - 관리자 실패 거래(`GET /api/v1/admin/topups/failures`)에 시간과 관계없이 `CHECK_REQUIRED`로 나오고 원인에 사유가 붙는다. `GET /api/v1/admin/topups/{id}/pg-payment`로 PG 상태·취소 합계를 우리 기록과 비교한 뒤 `POST /api/v1/admin/topups/{id}/manual-resolve`로 정리한다
  - 정리 방법: CANCELING은 `CONFIRM_CANCEL`(취소 확정)·`RESTORE`(되돌림, `CHARGE_CANCEL_RESTORE`), METHOD_CANCELING은 `CONFIRM_METHOD_CANCEL`(PG에서 전액 취소된 것을 확인해 확정)·`RETRY_METHOD_CANCEL`(전액 취소 다시 요청. 끝나지 않으면 표시가 지워진 채 METHOD_CANCELING으로 남아 대사가 다시 확인). 반영은 대사와 같은 메서드로 하고, 감사 로그 기능이 없어 관리자 ID·정리 방법·이전 상태를 INFO 로그로 남긴다

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
| | 429-LOGIN_LOCKED | 15분 안에 로그인 5회 실패 → 15분 잠금(5번째 실패부터, 가입하지 않은 이메일도 같음) |
| | 400-PASSWORD_MISMATCH | 탈퇴 시 비밀번호 재확인 불일치 (로그인 상태라 401 대신 400. 5회 틀리면 429-LOGIN_LOCKED) |
| | 400-KAKAO_AUTH_FAILED | 카카오가 인가 코드를 거절(`invalid_grant`): 코드 만료·재사용, redirect_uri 불일치. 키 설정 오류·카카오 장애는 502-EXTERNAL_SERVICE_ERROR |
| | 401-SOCIAL_NOT_LINKED | 카카오 로그인 시 연결된 회원이 없음 (카카오로는 가입하지 않음) |
| | 409-SOCIAL_ACCOUNT_LINKED_TO_OTHER | 연결하려는 카카오 계정이 다른 회원에 이미 연결됨 |
| | 409-SOCIAL_PROVIDER_ALREADY_LINKED | 회원에 다른 카카오 계정이 이미 연결됨 (해제 후 다시 연결) |
| | 409-WITHDRAWAL_NOT_ALLOWED | 진행 중 주문·판매, 미정산, 잔액, 결과 확인 중인 충전 건이 남았거나 판매자 철회 전 (`data.reasons`: `ACTIVE_ORDERS`, `ACTIVE_SALES`, `UNSETTLED_AMOUNT`, `BALANCE_REMAINING`, `PENDING_TOPUPS`, `SELLER_ACTIVE`) |
| | 404-SELLER_NOT_FOUND | 판매자 신청 내역 없음 (내 판매자 조회·수정·철회 요청, 관리자 판매자 조회·처리) |
| | 403-SELLER_SANCTIONED | 제재된 판매자의 재신청 |
| | 403-SELLER_NOT_APPROVED | APPROVED가 아닌 판매자의 판매 기능·정보 수정 (`MemberApi.getApprovedSellerByMemberId`) |
| | 400-SELLER_ACCOUNT_INCOMPLETE | 정산 계좌 수정 시 은행·계좌번호·예금주 중 일부만 보냄 |
| | 409-SELLER_ALREADY_APPLIED | 이 회원에게 진행 중인 판매자 신청(PENDING·APPROVED·WITHDRAW_REQUESTED)이 이미 있음 |
| | 409-BUSINESS_NUMBER_DUPLICATED | 다른 판매자가 이 사업자등록번호로 심사 중·판매 중·철회 심사 중이거나 제재됨 |
| | 409-SELLER_STATE_INVALID | 입점 승인·반려, 철회 요청·승인·거절, 제재를 할 수 없는 상태 |
| | 409-UNSETTLED_AMOUNT_EXISTS | 미정산 금액이 있어 철회 요청·철회 승인 불가 |
| | 409-ACTIVE_SALES_EXIST | 철회 승인 시 판매자 상품이 들어간 진행 중 주문(CREATED·PAYING·PAYMENT_FAILED·PAID·RETURN_REQUESTED)이 있음 |
| product (유진) | 404-PRODUCT_NOT_FOUND | 없거나 다른 판매자의 상품, 구매자에게 보이지 않는 상품(판매 중지·삭제·카테고리 사용 중지) |
| | 409-PRODUCT_NOT_ON_SALE | 판매 중이 아닌 상품 차감(주문), 관리자 즉시 중지 시 이미 중지·삭제된 상품 |
| | 409-PRODUCT_NOT_EDITABLE | 판매 중지·삭제된 상품의 수정·삭제·재고 조정·중지 요청 |
| | 400-STOCK_NEGATIVE | 재고 조정 결과가 음수 |
| | 400-STOCK_LIMIT_EXCEEDED | 재고를 늘리는 조정의 결과가 1,000,000개 초과 (주문 취소·반품 복원은 상한을 넘어도 받는다) |
| | 400-OUT_OF_STOCK | 주문 수량 > 재고 |
| | 400-THUMBNAIL_REQUIRED | 상품 등록 시 대표 이미지 없음 |
| | 400-IMAGE_TYPE_NOT_ALLOWED | jpg·png·webp가 아닌 이미지 (파일 내용으로 확인) |
| | 400-DETAIL_IMAGE_LIMIT_EXCEEDED | 상세 이미지 10개 초과 (수정은 남길 이미지 + 새 이미지 합계) |
| | 400-DETAIL_IMAGE_NOT_FOUND | 수정 시 남길 상세 이미지 ID가 이 상품의 것이 아니거나 중복 |
| | 409-STOP_REQUEST_PENDING | 심사 중인 판매 중지 요청이 이미 있음 |
| | 409-STOP_REQUEST_NOT_PENDING | 이미 승인·반려·자동으로 닫힌 요청을 다시 처리 |
| | 404-STOP_REQUEST_NOT_FOUND | 없는 판매 중지 요청 |
| | 409-PRODUCT_DELETED | 반품 승인 시 삭제된 상품의 재고 복원 선택 |
| | 409-OPTION_DELETED | 삭제된 옵션의 재고 조정, 반품 승인 시 삭제된 옵션의 재고 복원 선택 |
| | 404-CATEGORY_NOT_FOUND | 없는 카테고리 |
| | 409-CATEGORY_DUPLICATED | 같은 대분류 아래 이름 중복 |
| | 400-CATEGORY_DEPTH_EXCEEDED | 소분류 아래에 카테고리 추가 |
| | 400-CATEGORY_NOT_EDITABLE | 대분류 수정 |
| | 400-CATEGORY_NOT_SELECTABLE | 상품 등록·수정 시 사용 중인 소분류가 아닌 카테고리 선택 |
| | 400-INGREDIENTS_REQUIRED | 뷰티 소분류 상품을 전성분 없이(또는 공백으로) 등록·수정. 전성분이 없는 기존 뷰티 상품을 전성분 없이 수정하거나, 의류에서 뷰티로 옮기며 전성분을 안 보낸 경우 포함 |
| order (은정) | 400-SELF_PURCHASE_NOT_ALLOWED | 본인 상품 담기·주문 |
| | 400-STOCK_EXCEEDED | 장바구니 수량 > 재고 |
| | 409-PRICE_CHANGED | 예상 결제 금액(쿠폰 할인 후) ≠ 실제 결제 금액 (`data.currentAmount`) |
| | 409-CART_ITEM_IN_PENDING_ORDER | 결제 대기·결제 중(PAYING) 주문에 포함된 장바구니 항목 (`data.pendingOrderIds`: 그 주문 ID, 오름차순) |
| | 400-IDEMPOTENCY_KEY_INVALID | 주문 생성 `Idempotency-Key`가 비어 있거나 64자 초과 |
| | 409-IDEMPOTENCY_KEY_EXPIRED | 같은 `Idempotency-Key`로 만든 주문이 이미 취소(만료 포함)됨. 새 키로 다시 주문 |
| | 404-ORDER_NOT_FOUND | |
| | 409-ORDER_STATE_INVALID | 상태 전이 불가(취소·확정·반품 등). 반품 승인·거절은 반품 요청 중(RETURN_REQUESTED)이 아니면(이미 승인·거절됨 포함) 이 코드 |
| | 409-ORDER_EXPIRED | 결제 시점에 15분 경과 |
| | 409-PAYMENT_IN_PROGRESS | 결제 확인 중(PAYING)인 주문에 다시 결제·취소 요청. 결제 결과를 서버가 확인하는 중이므로 잠시 뒤 주문 상태를 다시 조회한다 |
| | 409-RETURN_ALREADY_REQUESTED | 반품 1회 사용 (거절된 뒤 다시 요청 포함) |
| | 400-RETURN_REASON_REQUIRED | 반품 사유 코드가 기타(OTHER)인데 상세 사유가 비어 있음 |
| | 400-RETURN_ITEMS_MISMATCH | 관리자 반품 승인에서 주문 항목마다 재고 복원 여부를 한 번씩 보내지 않음(항목 누락·중복·다른 주문의 항목) |
| | 409-ORDER_NOT_DELIVERED | 배송지가 있는 주문을 배송 완료(`deliveredAt`) 전에 구매 확정·반품 요청 |
| | 409-ORDER_COUPON_NOT_AVAILABLE | 주문에 쿠폰 반영이 꺼져 있을 때(custom.order.couponEnabled = false) 주문 생성에 쿠폰을 보냄 |
| payment (지은·시연) | 402-INSUFFICIENT_BALANCE | 잔액 부족. API 응답은 주문 결제가 order의 `OrderErrorCode.INSUFFICIENT_BALANCE`로 던진다(`data.shortage`·`recommendedTopupAmount`·`remainingSeconds`). payment 코드는 `Wallet` 출금 안전장치 |
| | 400-PAYMENT_PERIOD_INVALID | 조회 시작일 > 종료일 (거래 내역, 관리자 결제·충전 조회) |
| | 400-TOPUP_AMOUNT_INVALID | 1만 원 단위·1만~100만 원 위반 |
| | 400-AMOUNT_MISMATCH | 저장 금액 ≠ 승인 요청 금액 |
| | 400-TOPUP_PAYMENT_DECLINED | PG가 결제를 승인하지 않음(카드 거절·한도 초과 등) (`data.code`·`message`: PG 오류 코드·메시지. 헥토는 `outRsltCd`·`outRsltMsg`) |
| | 409-TOPUP_ALREADY_PROCESSED | 승인: 결제키·금액이 처음 요청과 다른 재요청, 실패·만료·취소된 충전 건, 다른 충전 건에 쓰인 결제키 (같은 요청의 재요청은 처음 결과를 `200-1`로 돌려줌). 실패 기록: READY가 아닌 충전 건 |
| | 409-TOPUP_NOT_CANCELABLE | 승인 완료(DONE)가 아니거나 취소할 남은 금액이 없는 충전 건에 취소 요청 |
| | 400-TOPUP_METHOD_NOT_ALLOWED | 승인된 결제가 충전에 허용하지 않은 결제 수단(카드·간편결제·계좌이체 외). 예치금에 반영하지 않고 결제를 자동 취소 |
| | 400-TOPUP_CANCEL_DECLINED | PG가 충전 취소를 거절했고, 조회한 취소 합계로 이번 취소가 반영되지 않았음을 확인함(환불 기간 지남·부분 취소 불가 등). 잔액은 되돌림 (`data.code`·`message`: PG 오류 코드·메시지). 확인하지 못하면 이 코드 대신 `200-1` + CANCELING |
| | 503-TOPUP_PG_UNAVAILABLE | 충전 준비 때 토스·헥토 모두 사용 불가(서킷 OPEN). 충전 건을 만들지 않음 |
| | 409-TOPUP_NOT_MANUAL_CHECK | 관리자 수동 확인 정리를 수동 확인으로 표시되지 않은 충전 건에 요청 (이미 정리됨 포함) |
| | 409-TOPUP_MANUAL_RESOLVE_NOT_ALLOWED | 관리자 수동 확인 정리에서 충전 상태에 맞지 않는 정리 방법 (CANCELING에 METHOD_CANCELING용 방법 등) |
| | 409-PAYMENT_ALREADY_PAID | 다른 멱등 키로 이미 PAID인 주문을 결제 (내부 `PaymentApi.pay`) |
| | 404-HOLDING_NOT_FOUND / 409-HOLDING_INSUFFICIENT / 409-TRANSFER_AMOUNT_MISMATCH | 정산 지급(`PaymentApi.transfer`)에서 홀딩 없음 / 홀딩 남은 금액 부족 / 주문별 금액 합 ≠ 지급액 + 수수료 |
| | 404-TOPUP_NOT_FOUND / 404-WALLET_NOT_FOUND | |
| settlement (다은) | 404-SETTLEMENT_NOT_FOUND | |
| | 409-SETTLEMENT_RUNNING | 같은 월 정산 실행 중 |
| | 409-SETTLEMENT_STATE_INVALID | 보류·해제·재처리를 할 수 없는 상태 |
| | 400-SETTLEMENT_MONTH_INVALID | 끝나지 않은 달 정산 시도 |
| | 404-SETTLEMENT_CANDIDATE_NOT_FOUND | 정산 후보를 찾을 수 없는 상태 |
| | 409-SETTLEMENT_CANDIDATE_STATE_INVALID | 정산 할 수 없는 정산 후보 상태가 정산을 시도 |
| | 409-SETTLEMENT_ALREADY_COMPLETED | 월 정산이 이미 완료된 달을 다시 실행 |
| | 409-SETTLEMENT_AMOUNT_MISMATCH | 정산 금액이 일치하지 않아서 정산할 수 없는 상태 |
| | 400-SETTLEMENT_CANDIDATE_AMOUNT_INVALID | 주문 항목 금액 또는 상품 쿠폰 할인액이 올바르지 않아 정산 후보를 만들 수 없는 상태 (처리 이력에만 남음) |
| | 500-SETTLEMENT_JOB_FAILED | JOB에 문제가 생긴 상태 |
| | 409-SETTLEMENT_CANDIDATE_AMOUNT_TAMPERED | 판별 단계: 정산 후보 금액 스냅샷(판매가·수수료·지급액)이 서로 맞지 않아 확정하지 않음 (처리 이력에만 남음) |
| | 409-SETTLEMENT_CANDIDATE_FEE_RATE_MISMATCH | 판별 단계: 정산 후보의 수수료율이 후보를 만든 시점의 정책 수수료율과 달라 확정하지 않음 (처리 이력에만 남음) |
| | 409-SETTLEMENT_LEDGER_MISSING / 409-SETTLEMENT_LEDGER_REPLACED / 409-SETTLEMENT_LEDGER_AMOUNT_CHANGED | 원장 재대조: 지급된 정산서의 원장 기록 없음 / 원장 행 교체 / 금액 변경 (처리 이력에만 남고, 그 판매자의 다음 지급을 보류) |
| | 409-SETTLEMENT_LEDGER_PAID_NOT_RECORDED | 출력 대사: 결제 원장에는 지급 기록이 있는데 정산서가 PAID가 아님 (처리 이력 RECONCILE에 남고 ERROR 로그로 알림, 정산 데이터는 고치지 않음) |
| | 400-SETTLEMENT_MONTH_RANGE_INVALID | 정산서 기간 조회·엑셀 다운로드 기간 오류(시작월 > 끝월, 최대 개월 수 초과, 정산월과 기간 동시 사용) |
| | 400-SETTLEMENT_EXPORT_TOO_LARGE | 정산서 엑셀에 담을 품목 수가 `custom.settlement.exportMaxRows`(기본 200,000)를 넘음(파일을 만들기 전에 거절) 또는 한 시트가 엑셀 최대 행 수를 넘음 |
| | 500-SETTLEMENT_EXPORT_FAILED | 정산서 엑셀 파일 생성 실패 |
| delivery (은정) | 404-DELIVERY_NOT_FOUND | 없는 배송 건 (물류 상세·송장 등록·수정·관리자 수동 완료) |
| | 409-DELIVERY_STATE_INVALID | READY가 아닌 배송에 송장 등록, SHIPPED가 아닌 배송의 송장 수정·관리자 수동 완료 |
| | 409-DELIVERY_TRACKING_DUPLICATED | 같은 택배사·송장번호(정규화 후)가 다른 배송 건에 이미 있음. 동시에 등록해 DB 유니크에 걸리면 409-DATA_CONFLICT |
| | 400-DELIVERY_TRACKING_NUMBER_INVALID | 공백·하이픈을 지운 송장번호가 영문·숫자 8~30자가 아님 |
| | 400-DELIVERY_DELIVERED_AT_INVALID | 관리자 수동 완료의 배송 완료 시각이 발송 시각보다 이름 |
| coupon (다은) | 404-COUPON_NOT_FOUND | 없는 쿠폰 (관리자 상세, 강제 중지, 예약) |
| | 400-COUPON_PERIOD_INVALID | 쿠폰 등록 시 시작이 종료보다 늦거나 같음, 종료가 현재 이전 |
| | 400-COUPON_TIER_INVALID | 장바구니 쿠폰 구간 없음, 할인액이 최소 주문 금액 이상, 구간이 올라가도 최소 주문 금액이나 할인액이 커지지 않음 |
| | 409-COUPON_STATE_INVALID | 이미 중지됐거나 유효 기간이 끝난 쿠폰을 중지 |
| | 409-COUPON_NOT_ACTIVE | 사용 기간 밖이거나 중지된 쿠폰을 예약, 또는 예약 뒤 중지됐거나 기간이 끝난 쿠폰으로 결제 (결제와 함께 롤백) |
| | 409-COUPON_SOLD_OUT | 수량 제한 쿠폰의 남은 수량 없음 (조건부 UPDATE로 판단) |
| | 409-COUPON_ALREADY_USED | 같은 회원이 같은 쿠폰을 이미 예약했거나 사용함 (1인 1회, 동시 요청은 부분 유니크 인덱스 위반을 이 코드로 바꿈) |
| | 409-COUPON_ORDER_LIMIT_EXCEEDED | 이 주문에 같은 종류의 쿠폰을 이미 예약했거나 사용함 (한 주문에 상품 쿠폰 1개 + 장바구니 쿠폰 1개까지, 동시 요청은 부분 유니크 인덱스 uk_coupon_usage_order_type 위반을 이 코드로 바꿈) |
| | 400-COUPON_NOT_APPLICABLE | 주문에 적용할 수 없는 쿠폰 (장바구니 쿠폰 자리에 상품 쿠폰 등), 상품 쿠폰 코드 검증에서 지정한 장바구니 항목이 판매 중인 대상 상품이 아님, 할인 후 금액이 0원이 되는 쿠폰(정액 할인액 ≥ 항목 금액) |
| | 400-COUPON_MIN_ORDER_NOT_MET | 장바구니 쿠폰의 가장 낮은 구간 최소 주문 금액에 못 미침 |
| | 404-COUPON_CODE_NOT_FOUND | 없는 상품 쿠폰 코드 (코드 검증, 대소문자 구분 없음) |
| | 409-COUPON_PERIOD_OVERLAPPED | 같은 인플루언서와 상품으로 유효 기간이 겹치는 운영 중 쿠폰이 있음 (발행, 수정) |
| | 409-COUPON_NOT_EDITABLE | 시작 후 쿠폰을 종료 일시 연장 말고 다르게 수정, 끝났거나 중지된 쿠폰 수정 |
| | 400-PRODUCT_NOT_OWNED | 상품 쿠폰 대상이 본인 상품이 아니거나 없는 상품 |
| | 409-PRODUCT_NOT_ON_SALE | 상품 쿠폰 대상 상품이 판매 중이 아님 |
| | 404-PARTNERSHIP_NOT_FOUND | 없는 협약 |
| | 409-PARTNERSHIP_ALREADY_EXISTS | 같은 인플루언서와 요청 중이거나 진행 중인 협약이 있음 (동시 요청은 부분 유니크 인덱스 위반을 이 코드로 바꿈) |
| | 409-PARTNERSHIP_STATE_INVALID | 요청 중이 아닌 협약 수락, 거절이나 진행 중이 아닌 협약 종료 |
| | 409-PARTNERSHIP_NOT_ACTIVE | 진행 중이 아닌 협약으로 쿠폰 발행 |
| | 404-INFLUENCER_NOT_FOUND | 협약 요청 대상 인플루언서가 없음 |
| | 409-INFLUENCER_STATE_INVALID | 승인되지 않은 인플루언서에게 협약 요청 |
| | 403-INFLUENCER_NOT_APPROVED | 승인된 인플루언서가 아닌 회원이 인플루언서 API 호출 |
| recommend (은정, ai) | 404-SURVEY_NOT_FOUND | 뷰티 설문을 아직 하지 않은 회원의 프로필 조회 |
| | 400-SURVEY_ANSWER_INVALID | 설문 답이 칸에 맞지 않음: 선호 성분 칸에 피하는 성분 코드(또는 반대), 같은 코드 중복, 고민 4개 이상(도메인 검사. 요청 검증에서는 400-VALIDATION_FAILED). 모르는 코드는 400-INVALID_REQUEST |
| faq (시연, ai) | 404-FAQ_NOT_FOUND | 관리자가 없는 FAQ를 수정·노출 변경 |
| chat (시연, ai) | 404-CONVERSATION_NOT_FOUND | 없는 대화로 질문하거나 대화 메시지 조회. 다른 회원의 대화도 같음(있는지 알려 주지 않음) |
| | 429-TOO_MANY_REQUESTS | 챗봇 질문이 회원별 1분에 10번을 넘음(`GlobalErrorCode`, `custom.chat.rateLimit*`). OpenAI를 부르기 전에 막는다 |
| | 502-EXTERNAL_SERVICE_ERROR | 질문 임베딩(OpenAI) 실패. 답변 생성 실패는 오류가 아니라 FAQ 답변으로 대신한다(6.4) |


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
| Security `AuthenticationEntryPoint`·`AccessDeniedHandler` | 완료 (`JwtAuthenticationEntryPoint`·`JwtAccessDeniedHandler`) |
| 토스 연동 예외(`TossPaymentException`·`TossTimeoutException`)·충전 대사 스케줄러(승인·취소·허용하지 않은 결제 수단 취소) | 완료 |
| 헥토 연동 예외(`HectoResultUnknownException`, 응답 코드 → PG 중립 결과·예외)·결제창 결과 엔드포인트 | 완료 |
| 충전 승인 결과 모름 시 즉시 조회·1회 재승인, PG별 서킷 브레이커·충전 준비 PG 자동 선택 | 완료 |
| 챗봇 OpenAI 실패 시 FAQ 답변 대체·요청 수 제한(Redis) | 완료 |
| 요청별 `traceId`(MDC, `X-Trace-Id` 헤더) | 미구현 |
| 운영 프로필에서 `show-sql` 끄기 | 미구현 |
