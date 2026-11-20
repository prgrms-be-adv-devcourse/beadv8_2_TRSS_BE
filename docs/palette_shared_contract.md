# shared 계약 초안

컨텍스트끼리 주고받는 **동기 Api 인터페이스, DTO, 이벤트**의 초안입니다. 규칙은 [개발 가이드 6장](palette_development_guide.md#6-컨텍스트-간-통신)을 따릅니다.

- 이 문서는 **초안**입니다. 각 담당자가 자기 컨텍스트 부분을 검토해 `shared/<ctx>` 코드로 옮기고, 바꾼 내용은 이 문서에도 반영합니다.
- 메서드와 필드는 [기획서 3.7 모듈 간 내부 API](palette_proposal.md)와 [정책](palette_policy.md)을 기준으로 뽑았습니다.


| 컨텍스트 | 담당 | 제공하는 Api | 발행하는 이벤트 |
| --- | --- | --- | --- |
| member | 시연·지은 | `MemberApi` | `MemberSignedUpEvent`, `SellerSanctionedEvent`, `SellerWithdrawalRequestedEvent`, `SellerWithdrawalRejectedEvent`, `SellerBusinessNameChangedEvent` |
| product | 유진 | `ProductApi` | `WishAddedEvent`, `WishRemovedEvent`, `ProductChangedEvent`, `ProductMetricsChangedEvent` |
| review | 유진 | `ReviewApi` | `ReviewCreatedEvent`, `ReviewModifiedEvent`, `ReviewDeletedEvent` |
| order | 은정 | `OrderApi` | `OrderPaidEvent`, `OrderConfirmedEvent`, `OrderReturnedEvent` |
| payment | 지은·시연 | `PaymentApi` | - |
| settlement | 다은 | `SettlementApi` | - |
| delivery | 은정 | `DeliveryApi` | `DeliveryShippedEvent`, `DeliveryCompletedEvent` |
| notification | 시연 | - | - |

### 이벤트 공통 규격

```java
package com.backend.shared.event;

/** 다른 컨텍스트에 알리는 이벤트 record가 구현한다 */
public interface DomainEvent {
    String aggregateType();   // 이벤트가 생긴 대상 종류. 대문자 단수형(ORDER, MEMBER)
    Long aggregateId();       // 그 대상의 ID
}

/** 모든 이벤트에 붙는 봉투. EventEnvelope.wrap(event)로 만든다 */
public record EventEnvelope<T extends DomainEvent>(UUID eventId, String eventType, LocalDateTime occurredAt,
                                                   String aggregateType, Long aggregateId, T payload) { }
```

- 아래 컨텍스트별 이벤트는 모두 `DomainEvent`를 구현한다. 새 이벤트를 추가할 때도 구현한다.
- 봉투는 Outbox 도입(TRSS-121)에서 쓴다. 단계와 필드 설명은 [개발 가이드 6.5](palette_development_guide.md#65-outbox)
- 이벤트 필드를 바꿀 때는 보내는 쪽과 받는 쪽 테스트를 함께 확인한다. 서비스가 나뉘면 한 앱에서 양쪽을 같이 돌리던 통합 테스트가 없어지고 각자 목으로 따로 검증하게 되므로, 한쪽만 고치면 테스트는 통과해도 실제로는 맞지 않을 수 있다 (예: 회원 → 상품 판매 중지 흐름은 market 분리 때 목 없는 통합 테스트가 빠짐)

### 내부 API 공통 규칙

서비스끼리 HTTP로 부르는 내부 API(`/internal/**`)는 사용자 JWT 대신 **내부 전용 키**로 인증한다(TRSS-162).

```http
GET /internal/v1/members/1
X-Internal-Key: <INTERNAL_API_KEY 값>
```

| 요청 | 응답 |
| --- | --- |
| `X-Internal-Key`가 설정값과 같음 | 통과 (JWT 없어도 됨) |
| 키가 없거나 다름 | 401 `401-INTERNAL_API_KEY_INVALID` ("내부 API 키가 없거나 올바르지 않습니다.") |
| 사용자 JWT(`Authorization: Bearer`)만 있음 | 401 `401-INTERNAL_API_KEY_INVALID` (사용자 토큰으로는 내부 API를 부를 수 없다) |

- 키는 환경 변수 `INTERNAL_API_KEY`(설정 `custom.internal.apiKey`)로 넣고 기본값이 없다. 없으면 앱이 뜨지 않는다. 지금은 모든 서비스가 같은 값을 쓴다.
- 부르는 쪽 HTTP 클라이언트(`RestClient`)는 모든 요청에 `X-Internal-Key` 헤더를 기본으로 붙인다.
- 내부 키 오류(`401-INTERNAL_API_KEY_INVALID`)는 부르는 쪽 설정 문제라 사용자에게 401로 넘기지 않고 조회 실패로 처리하며, ERROR 로그를 남긴다. 401을 그대로 넘기면 프론트가 로그인 만료로 처리할 수 있고, 4xx라 오류 알림도 오지 않는다. 같은 401이라도 상대가 판단한 결과(예: 회원 확인의 `401-UNAUTHORIZED`)는 그대로 전달한다.

| 부르는 쪽 | 내부 키 오류를 받으면 |
| --- | --- |
| 회원 (`MemberInternalApiClient`) | `MemberPortUnavailableException` (탈퇴·철회 검사는 503 `WITHDRAWAL_CHECK_UNAVAILABLE`, 관리자 회원 상세는 그 영역만 비움) |
| 결제 → 회원 (`PaymentMemberHttpAdapter`) | 502 `EXTERNAL_SERVICE_ERROR` |
| 주문 → 결제 | 503 `INTERNAL_API_AUTH_FAILED` (결제 `pay` 설명 참고) |
- 외부에는 열지 않는다. nginx는 `/api/` 등만 앱으로 넘기고 `/internal/`은 넘기지 않는다. 게이트웨이를 만들 때도 `/internal/**`은 라우팅하지 않는다.
- 내부 API 컨트롤러는 Swagger에 나오지 않게 `@Hidden`을 붙이고, 응답은 다른 API와 같은 `RsData`로 돌려준다.

## 1. 시나리오별 호출 흐름

계약이 왜 필요한지 보여 주는 표입니다. "같은 Tx"는 호출한 쪽 트랜잭션에 참여한다는 뜻입니다.

| 시나리오 | 시작 | 호출 순서 | 방식 |
| --- | --- | --- | --- |
| 회원 가입 | member | 회원 저장 → `MemberSignedUpEvent` → (커밋 뒤 Kafka `member.events`) 결제가 지갑 생성 | 이벤트, 별도 Tx (지갑 생성이 실패해도 가입은 성공) |
| 장바구니 조회 | order | `ProductApi.getOptions(optionIds)` → 현재 가격·판매 가능 여부·대표 이미지로 예상 금액 계산 | 동기 조회 |
| 장바구니 담기·주문 생성 시 본인 상품 검사 | order | `MemberApi.findSellerByMemberId(buyerId)` → 상품의 `sellerId`와 비교 | 동기 조회 |
| 주문 생성 | order | 장바구니 잠금 → (같은 `Idempotency-Key`의 주문이 있으면 그 주문을 반환하고 끝, 취소된 주문이면 409) → `ProductApi.getOptions` → `MemberApi.getSellers`(판매자명 스냅샷) → 주문·주문 항목 저장 → `ProductApi.deductStock`. 응답에 주문 상태(`status`: 새 주문 CREATED, 같은 키 재요청은 그 주문의 지금 상태)를 담는다 | 동기, 같은 Tx |
| 결제 | order | 판매 가능·만료·예약 쿠폰 확인 → 주문 PAYING(멱등키·시작 시각) 커밋 → `PaymentApi.pay(command)`(HTTP): 배송비는 구매자 지갑 → 플랫폼 지갑(SHIPPING_FEE), 나머지는 홀딩(PAYMENT), 장바구니 쿠폰 지원금은 플랫폼 지갑 → 홀딩(COUPON_SUBSIDY) → 주문을 다시 잠가 아직 PAYING이고 같은 키일 때만 결과 반영(PAID: 쿠폰 사용 처리·장바구니 삭제·`OrderPaidEvent` / FAILED_*: PAYMENT_FAILED). 결과를 모르면 PAYING으로 두고 재확인 스케줄러가 같은 키로 `pay`를 다시 불러 정리 (9.1) | 동기 HTTP, Tx 2개 |
| 결제 후 배송 생성 | order | 결제 성공 + 배송지가 있는 주문이면 `OrderPaidEvent` → (커밋 뒤 Kafka `order.events`) delivery가 배송(READY) 생성, 알림이 구매자에게 주문 완료 알림 | 이벤트, 별도 Tx (주문 PAID 반영과 같은 트랜잭션에서 outbox에 저장) |
| 배송 시작 | delivery | 물류 담당 송장 등록 → 배송 SHIPPED → `DeliveryShippedEvent` → (Kafka `delivery.events`) 알림이 구매자에게 배송 시작 알림 | 이벤트, 별도 Tx (송장 수정은 이벤트 없음) |
| 배송 완료 | delivery | Delivery Tracker로 배송 완료 확인(또는 관리자 수동 완료 `POST /api/v1/admin/deliveries/{deliveryId}/complete`, 같은 이벤트) → 배송 DELIVERED → `DeliveryCompletedEvent` → (Kafka `delivery.events`) market이 주문 `deliveredAt` 기록, 알림이 구매자에게 배송 완료 알림 | 이벤트, 별도 Tx |
| 주문 상세의 배송 정보 | order | `DeliveryApi.findByOrderId(orderId)` (배송지 있고 PAID 이후 주문만) | 동기 조회 (조회 실패 시 주문 상세는 200 + `deliveryUnavailable: true`) |
| 주문 상세의 항목 이미지 | order | `ProductApi.getOptions(optionIds)` → 항목별 상품의 현재 대표 이미지(`items[].thumbnailUrl`, 판매 중지·삭제 상품 포함) (TRSS-242) | 동기 조회 (같은 서비스 DB, 주문 조회와 같은 읽기 전용 트랜잭션. 조회 실패 시 주문 상세도 실패) |
| 주문 취소·15분 만료 | order | `ProductApi.lockProductsForOrderChange(productIds)` → 주문 잠금·상태 다시 확인 → 주문 CANCELED → `ProductApi.restoreStock(ORDER_RESTORE)` | 동기, 같은 Tx |
| 반품 승인 | order | 주문 RETURNED → 복원 선택 항목만 `ProductApi.restoreStock(RETURN_RESTORE)` → `OrderReturnedEvent` → (커밋 뒤 Kafka `order.events`) | 동기, 같은 Tx + 이벤트 (재고 복원과 outbox 저장이 승인과 같은 트랜잭션) |
| 반품 환불 | payment | `OrderReturnedEvent` 수신(Kafka `order.events`). 반품 사유(`returnReasonCode`)로 나눈다. 상품불량·기타·사유 없음: 홀딩 → 구매자 지갑(결제 금액 전액, REFUND), 플랫폼 지갑에서 처음 배송비를 내놓음(SHIPPING_FEE_REFUND). 단순변심: 회수 택배비 결제 대기(`PICKUP_FEE_REQUIRED`) → 구매자가 반품 회수 택배비(2,500원)를 예치금으로 결제(`POST /api/v1/payments/refunds/{orderId}/pickup-fee`, 구매자 지갑 → 플랫폼 지갑, RETURN_PICKUP_FEE_PAYMENT·RETURN_PICKUP_FEE)하면 홀딩 → 구매자 지갑(상품값만, REFUND). 두 경우 모두 장바구니 쿠폰 지원금은 홀딩 → 플랫폼 지갑(COUPON_SUBSIDY_CANCEL) → 결제 REFUNDED, 홀딩 RELEASED | 이벤트, 별도 Tx (회수 택배비 결제는 구매자 API, 한 Tx) |
| 구매 확정 (수동·7일 자동·반품 거절 시 즉시, 배송지 있는 주문은 배송 완료 뒤) | order | 주문 CONFIRMED → `OrderConfirmedEvent` | 이벤트 |
| 정산 후보 생성 | settlement | `OrderConfirmedEvent` 수신(Kafka `order.events`) → 주문 항목별 후보(READY) | 이벤트 |
| 월 정산 지급 | settlement | ⓪ 이전에 지급을 시도한 정산서(FAILED·MANUAL_REQUIRED)는 `PaymentApi.findTransfer`로 먼저 확인(잠금 밖, 지급 기록이 있으면 transfer 없이 PAID·금액이 다르면 MANUAL_REQUIRED로 확정, 조회 실패면 그대로 진행) → `MemberApi.getSeller(sellerId)`(잠금 밖) → ① 정산서 잠금·확인 후 PROCESSING 커밋 → ② `PaymentApi.transfer(command)`(트랜잭션 밖) → ③ 결과 기록(PAID / 4xx면 FAILED·MANUAL_REQUIRED / 결과 모름이면 PROCESSING 유지, 재확인 스케줄러가 `findTransfer`로 확정) | 동기(HTTP), 트랜잭션 세 개 |
| 상품 삭제·관리자 즉시 중지·판매 중지 요청 승인 | product | 상품 잠금 → 상품 DELETED/STOPPED → 심사 중인 중지 요청 CANCELED → `OrderApi.cancelPendingOrders(productIds, PRODUCT_STOPPED)` → (주문이) `ProductApi.restoreStock` | 동기, 같은 Tx |
| 판매자 철회 요청 | member | `SettlementApi.getUnsettledAmount(sellerId)` 0인지 확인 → 판매자 WITHDRAW_REQUESTED → `SellerWithdrawalRequestedEvent` → (market) 판매 중 상품 STOPPED, 결제 대기 주문 취소·재고 복원 | 이벤트 (미정산 확인은 동기 조회, 조회 실패 시 503) |
| 판매자 철회 승인 | member | `OrderApi.hasActiveSales(sellerId)` · `SettlementApi.getUnsettledAmount(sellerId)` 확인 → 판매자 WITHDRAWN (상품은 철회 요청 때 이미 중지) | 동기 조회 (조회 실패 시 503) |
| 판매자 철회 거절 | member | 판매자 APPROVED로 되돌림 → `SellerWithdrawalRejectedEvent` → (market) 철회로 중지된 상품만 판매 중으로 | 이벤트 |
| 관리자 제재 | member | 판매자 SANCTIONED → `SellerSanctionedEvent` → (market) 판매 중 상품 STOPPED · (settlement) 지급 전 정산서 HOLD | 이벤트 |
| 회원 탈퇴 | member | 판매자 상태가 PENDING·APPROVED·WITHDRAW_REQUESTED면 거절(SELLER_ACTIVE) → `OrderApi.hasActiveOrders` · `PaymentApi.getWallet`(잔액 0) · `PaymentApi.hasPendingTopups`(READY·PROCESSING·CANCELING·METHOD_CANCELING 충전 건) · 판매자 정보가 있으면 상태와 무관하게 `OrderApi.hasActiveSales` · `SettlementApi.getUnsettledAmount` | 동기 조회 (조회 실패 시 503, 지갑이 없으면 잔액 0) |
| 충전 준비·승인, 주문 생성·결제 | payment, order | `MemberApi.checkActiveMember(memberId)`: 탈퇴한 회원이면 401 (탈퇴 후 남은 Access Token 차단) | 동기 조회 |
| 관리자 회원 상세 | member | `PaymentApi.getWallet` · `OrderApi.getRecentOrders(memberId, 5)` | 동기 조회 (조회 실패 시 해당 영역만 null) |
| 관리자 결제·충전 조회 | payment | `MemberApi.findMemberByEmail`(이메일 검색) · `MemberApi.getMembers`(이메일 표시) | 동기 조회 |

## 2. member

회원은 `member-service`(포트 8081, DB 스키마 `member`)로 옮겼습니다. 다른 서비스와는 내부 API(HTTP)와 이벤트(Kafka `member.events`·`seller.events`)로만 연결됩니다.

### MemberApi

```java
package com.backend.shared.member.out;

public interface MemberApi {

    /** 회원 조회. 없으면 404 MEMBER_NOT_FOUND */
    MemberDto getMember(Long memberId);

    /** 로그인 상태인 회원인지 확인. 없거나 탈퇴했으면 401 UNAUTHORIZED (충전 준비·승인, 주문 생성·결제에서 호출) */
    void checkActiveMember(Long memberId);

    /** 여러 회원 조회 (관리자 결제·충전 조회의 이메일 표시). 없는 ID는 결과에서 빠짐 */
    List<MemberDto> getMembers(Collection<Long> memberIds);

    /** 이메일로 회원 조회 (앞뒤 공백·대소문자를 무시하고 정확히 일치, 관리자 결제·충전 검색). 없으면 empty */
    Optional<MemberDto> findMemberByEmail(String email);

    /** 회원의 판매자 정보 (회원당 1개, 상태 무관). 판매자 신청 이력이 없으면 empty */
    Optional<SellerDto> findSellerByMemberId(Long memberId);

    /** 판매 기능을 쓸 수 있는(APPROVED) 판매자 조회. 아니면 403 SELLER_NOT_APPROVED */
    SellerDto getApprovedSellerByMemberId(Long memberId);

    /** 판매자 조회. 없으면 404 SELLER_NOT_FOUND */
    SellerDto getSeller(Long sellerId);

    /** 여러 판매자 조회 (주문 스냅샷·정산 목록용). 없는 ID는 결과에서 빠짐 */
    List<SellerDto> getSellers(Collection<Long> sellerIds);
}
```

### 내부 API (HTTP)

다른 서비스는 `MemberApi` 대신 아래 내부 API를 HTTP로 부릅니다. 부르는 쪽 클라이언트는 각 모듈이 필요한 메서드만 담아 만듭니다([개발 가이드 6.2](palette_development_guide.md)).

| 부르는 쪽 | 클라이언트 | 주소 설정 |
| --- | --- | --- |
| market-service (상품·주문) | `market.client.MemberApiClient` | `custom.member.baseUrl` (`MEMBER_SERVICE_URL`, 기본 `http://localhost:8081`) |
| 루트 모놀리스 (결제) | `monolith.client.MemberApiClient` | `custom.member.baseUrl` (`MEMBER_SERVICE_URL`, 기본 `http://localhost:8081`) |

- 운영(`infra/compose.prod.yaml`)에서는 `MEMBER_SERVICE_URL: http://member:8081`로 Docker 내부망에서 직접 부르고 nginx를 거치지 않는다.
- 외부 요청은 nginx가 회원 경로(`/api/v1/auth`·`users`·`sellers`·`seller/me`·`admin/users`·`admin/sellers`)를 member-service로 넘긴다. `seller/products`·`seller/order-items`(market), `seller/settlements`(정산)는 각 서비스로 간다.

| MemberApi | 요청 | 없을 때·실패 |
| --- | --- | --- |
| `getMember` | `GET /internal/v1/members/{memberId}` | 404-MEMBER_NOT_FOUND |
| `checkActiveMember` | `GET /internal/v1/members/{memberId}/active` | 401-UNAUTHORIZED (없거나 탈퇴) |
| `getMembers` | `GET /internal/v1/members?ids=1,2,3` | 없는 ID는 빠짐, `ids`가 없으면 빈 목록 |
| `findMemberByEmail` | `POST /internal/v1/members/search-by-email` 본문 `{"email": "..."}` | 200 + `data: null` |
| `getSeller` | `GET /internal/v1/sellers/{sellerId}` | 404-SELLER_NOT_FOUND |
| `getSellers` | `GET /internal/v1/sellers?ids=1,2,3` | 없는 ID는 빠짐, `ids`가 없으면 빈 목록 |
| `findSellerByMemberId` | `GET /internal/v1/sellers/by-member/{memberId}` | 200 + `data: null` (신청 이력 없음) |
| `getApprovedSellerByMemberId` | `GET /internal/v1/sellers/by-member/{memberId}/approved` | 403-SELLER_NOT_APPROVED |

- 응답은 다른 API와 같은 `RsData`이고 성공은 `200-1`, `data`에 `MemberDto`·`SellerDto`(또는 목록)를 담습니다.
- 실패는 `MemberApi`와 같은 `resultCode`와 HTTP 상태로 돌려줍니다. 부르는 쪽은 `resultCode`로 같은 `DomainException`을 다시 만들 수 있습니다.
- 결과가 없을 수 있는 조회(`Optional`)는 404가 아니라 200 + `data: null`입니다.
- 이메일은 URL·접근 로그에 남지 않도록 본문으로 받습니다. 형식 검증은 하지 않고, 형식이 맞지 않으면 없는 것으로 응답합니다.
- Swagger에는 나오지 않습니다(`@Hidden`). 호출할 때는 `X-Internal-Key` 헤더가 필요합니다 → [내부 API 공통 규칙](#내부-api-공통-규칙)

### 회원이 부르는 다른 모듈 (TRSS-145)

회원은 다른 모듈을 자기 Port(`member/out`)로만 부릅니다. 다른 서비스로 옮겨 간 모듈은 내부 API를 HTTP로 부릅니다.

| Port | 구현 | 부르는 곳 | 주소 설정 |
| --- | --- | --- | --- |
| `MemberOrderPort` | `MemberOrderHttpAdapter` | market-service `/internal/v1/orders` | `custom.market.baseUrl` (`MARKET_SERVICE_URL`, 기본 `http://localhost:8082`) |
| `MemberSettlementPort` | `MemberSettlementHttpAdapter` | settlement-service `/internal/v1/settlements` (정산 보류는 HTTP가 아니라 `SellerSanctionedEvent`) | `custom.settlement.baseUrl` (`SETTLEMENT_SERVICE_URL`, 기본 `http://localhost:8084`) |
| `MemberPaymentPort` | `MemberPaymentHttpAdapter` | payment-service `/internal/v1/payments` | `custom.payment.baseUrl` (`PAYMENT_SERVICE_URL`, 기본 `http://localhost:8083`) |

- HTTP 호출은 `MemberInternalApiClient`가 맡습니다. 모든 요청에 `X-Internal-Key`를 붙이고, 접속 1초·응답 3초 제한을 둡니다.
- 실패는 두 가지로 나눕니다.
  - 상대가 4xx `RsData` 오류로 응답: 같은 `resultCode`의 `DomainException` (예: `404-WALLET_NOT_FOUND`)
  - 응답을 받지 못함(접속 실패·시간 초과), 상대 5xx, 읽을 수 없는 응답: `MemberPortUnavailableException` (확인하지 못함)
- 확인하지 못했을 때의 처리는 값의 쓰임에 따라 다릅니다.

| 쓰임 | 해당 기능 | 확인하지 못하면 |
| --- | --- | --- |
| 판단 (통과 여부 결정) | 회원 탈퇴 조건 조회·요청, 판매자 철회 요청·승인 | 통과시키지 않고 `503-WITHDRAWAL_CHECK_UNAVAILABLE` ("탈퇴 가능 여부를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.") |
| 표시 (보여 주기만 함) | 내 판매자 정보 조회·수정의 `unsettledAmount`, 관리자 회원 상세의 `wallet`·`recentOrders` | 그 값만 `null`로 비우고 나머지는 그대로 응답 (빈 목록과 `null`은 다르다: `null`은 조회하지 못함). 관리자 회원 상세의 지갑은 `walletState: UNAVAILABLE`로 알림 |

- 결제의 `404-WALLET_NOT_FOUND`(지갑이 아직 없음)는 `MemberPaymentHttpAdapter`만 해석해 `findWallet`의 `Optional.empty()`로 바꿉니다. 다른 4xx는 위 규칙대로 같은 `resultCode`로 던집니다.
  - 탈퇴 검사: 지갑이 없으면 잔액 0으로 봅니다 (돌려줄 예치금이 없음).
  - 관리자 회원 상세: `walletState`로 구분합니다. `CREATED`(지갑 있음, `wallet`에 잔액) / `NOT_CREATED`(지갑 없음) / `UNAVAILABLE`(결제 조회 실패). `wallet`은 `CREATED`일 때만 있습니다.
- 판단에 쓰는 여부·금액 응답의 `data`가 비어 있으면 `false`·0으로 통과시키지 않고 확인하지 못한 것으로 봅니다.

### DTO

```java
package com.backend.shared.member.dto;

public record MemberDto(Long id, String email, String name, MemberRole role, MemberStatus status) { }

/** 계좌 정보는 넣지 않는다 (정산은 예치금 지갑으로 지급) */
public record SellerDto(Long sellerId, Long memberId, String businessName, SellerStatus status) {
    public boolean canSell() { return status == SellerStatus.APPROVED; }
}

public enum MemberRole { USER, ADMIN }   // 판매자는 역할이 아니라 SellerStatus로 판단
public enum MemberStatus { ACTIVE, DELETED }
public enum SellerStatus { PENDING, APPROVED, REJECTED, WITHDRAW_REQUESTED, WITHDRAWN, SANCTIONED }
```

### 이벤트

```java
package com.backend.shared.member.event;

/** 회원 가입 완료. 결제가 받아 잔액 0인 지갑을 생성한다. aggregate: MEMBER / memberId */
public record MemberSignedUpEvent(Long memberId) implements DomainEvent { }

/** 관리자 제재. 상품이 받아 판매 중 상품을 중지하고, 정산이 받아 정산을 보류한다. aggregate: SELLER / sellerId */
public record SellerSanctionedEvent(Long sellerId, String reason) implements DomainEvent { }

/** 판매자 철회 요청. 상품이 받아 판매 중 상품을 중지한다. aggregate: SELLER / sellerId */
public record SellerWithdrawalRequestedEvent(Long sellerId, String reason) implements DomainEvent { }

/** 관리자 철회 거절. 상품이 받아 철회 요청 때 중지된 상품을 다시 판매 중으로 되돌린다. aggregate: SELLER / sellerId */
public record SellerWithdrawalRejectedEvent(Long sellerId) implements DomainEvent { }

/**
 * 승인된 판매자가 상호를 바꿈(판매자 정보 수정). 앞뒤 공백을 뺀 상호가 실제로 바뀔 때만 발행한다
 * (연락처·계좌만 바꾸거나 같은 상호로 다시 저장하면 발행하지 않고, 재신청으로 바뀌는 상호도 발행하지 않는다).
 * 상품 검색 색인이 받아 그 판매자의 판매 중 상품을 새 상호명으로 다시 색인한다. aggregate: SELLER / sellerId (TRSS-331)
 */
public record SellerBusinessNameChangedEvent(Long sellerId, String businessName) implements DomainEvent { }
```

- 가입 이벤트는 가입 트랜잭션에서 outbox에 저장되고, 릴레이가 커밋 뒤 토픽 `member.events`로 보낸다. 결제가 Kafka 컨슈머(groupId `payment`)로 받아 별도 트랜잭션에서 지갑을 만든다(TRSS-138). 지갑 생성이 실패해도 가입은 롤백되지 않는다.
- 그래서 가입 직후 지갑이 아직 없는 구간이 있다(보통 몇 초). 이 구간의 충전·결제 처리는 결제 담당이 정한다(#194).
- 판매자 이벤트(TRSS-144)는 토픽 `seller.events`로 나가고 키는 `sellerId`다. 같은 판매자의 이벤트는 같은 파티션으로 가서 발행 순서대로 처리된다. 사유(`reason`)는 앞뒤 공백을 뺀 값이다.
- 판매자 이벤트는 상태 변경과 같은 트랜잭션에서 outbox에 저장한다. 상품 중지·재개는 market 컨슈머(groupId `market`)가 이벤트로 처리하고 회원은 상품을 부르지 않는다(TRSS-160).
- 정산 보류도 정산 컨슈머(groupId `settlement`)가 이벤트로 처리하고 회원은 정산을 부르지 않는다(TRSS-161). 이벤트가 오기 전에 지급이 돌면 정산이 지급 직전에 판매자 상태를 보고 보류한다.

## 3. product

### ProductApi

```java
package com.backend.shared.product.out;

/** 재고는 옵션 단위다. 차감·복원·잠금은 호출한 쪽 트랜잭션에 참여하고, 예외는 잡지 않고 그대로 위로 던진다. */
public interface ProductApi {

    /**
     * 옵션 여러 개의 현재 정보. 장바구니 담기·조회와 주문 생성에 쓴다.
     * 삭제된 상품·옵션도 결과에 포함하고(saleable = false), 없는 ID만 빠진다. 결과 순서는 보장하지 않는다.
     * price는 부르는 시각에 진행 중인 상품 할인을 반영한 단가다.
     */
    List<OptionSnapshotDto> getOptions(Collection<Long> optionIds);

    /**
     * 상품 여러 개의 현재 정보 (상품 단위). 쿠폰 발행에서 쓴다. price는 할인과 무관한 정가다.
     * 삭제, 판매 중지 상품도 포함하고(onSale = false) 없는 ID만 빠진다. 순서는 보장하지 않는다.
     */
    List<ProductSnapshotDto> getProducts(Collection<Long> productIds);

    /**
     * 주문 생성 시 재고 차감(ORDER_DEDUCT). 주문 항목을 저장해 orderItemId가 생긴 뒤 같은 트랜잭션에서 호출한다.
     * 상품(읽기 잠금) → 옵션 재고(쓰기 잠금) 순서로, 각각 ID 오름차순으로 잠근다. 옵션 행은 잠그지 않는다
     * (재고는 option_stock에 따로 있어서, 판매자의 옵션 추가금 수정과 서로 기다리지 않는다).
     * 실패: 409 PRODUCT_NOT_ON_SALE(판매 불가), 400 OUT_OF_STOCK(재고 부족), 404 PRODUCT_NOT_FOUND (하나라도 실패하면 전체 롤백)
     * 재고만 바꾸고 상품 상태는 바꾸지 않는다(품절은 재고로 계산).
     * 이미 차감한 주문 항목으로 다시 호출하면 아무것도 하지 않고 정상 종료한다.
     * 호출한 쪽 트랜잭션에 참여만 한다(Propagation.MANDATORY). 트랜잭션 밖에서 부르면 IllegalTransactionStateException.
     */
    void deductStock(List<StockChangeDto> items);

    /**
     * 재고 복원. 주문 취소·만료·판매 중지로 인한 취소는 ORDER_RESTORE, 반품 승인은 RETURN_RESTORE.
     * 재고만 바꾸고 상품 상태는 바꾸지 않는다(STOPPED 상품도 재고만 늘어난다).
     * 삭제된 상품·옵션은 ORDER_RESTORE만 재고를 늘리고, RETURN_RESTORE면 409 PRODUCT_DELETED / OPTION_DELETED
     * (주문 컨텍스트가 반품 승인에서 삭제된 대상을 미리 거른다).
     * 이미 판 수량을 돌려놓는 것이라 옵션 최대 재고(1,000,000)를 넘어도 받는다(주문 취소·반품 승인이 재고 상한 때문에 실패하지 않게).
     * 같은 주문 항목·사유로 다시 호출하면 아무것도 하지 않고 정상 종료한다. 차감한 적 없는 항목이면 IllegalStateException.
     * 주문 상태 변경과 같은 트랜잭션에서만 부른다(Propagation.MANDATORY). 트랜잭션 밖에서 부르면 IllegalTransactionStateException.
     */
    void restoreStock(List<StockChangeDto> items, StockRestoreReason reason);

    /**
     * 재고를 복원할 주문을 잠그기 전에 그 주문의 상품을 ID 순으로 읽기 잠금한다(구매자 취소·결제 만료·반품 승인).
     * 순서: 주문 항목의 productId 모으기 → 이 메서드 → 주문 잠금 → 주문 상태 다시 확인 → 취소·restoreStock.
     * 트랜잭션 밖에서 부르면 IllegalTransactionStateException. 없는 상품 ID는 건너뛴다.
     */
    void lockProductsForOrderChange(Collection<Long> productIds);

    /**
     * 판매자의 판매 중(ON_SALE) 상품을 모두 STOPPED(stoppedBy = SELLER_WITHDRAWN)로 바꾼다(판매자 철회·관리자 제재).
     * 이미 중지·삭제된 상품은 건드리지 않는다. 심사 중인 중지 요청을 닫고 OrderApi.cancelPendingOrders도 호출한다.
     * 판매 중 상품이 없으면 아무것도 하지 않는다. 다시 호출해도 결과가 같다.
     */
    void stopAllBySeller(Long sellerId, String reason);

    /**
     * 판매자 철회로 중지된(stoppedBy = SELLER_WITHDRAWN) 상품만 다시 ON_SALE로 되돌린다(철회 거절).
     * 관리자·판매 중지 요청으로 중지된 상품과 삭제된 상품은 그대로다. 취소된 주문은 되살리지 않는다.
     */
    void resumeAllBySeller(Long sellerId);
}
```

### 내부 API (HTTP)

다른 서비스는 `ProductApi` 대신 아래 내부 API를 HTTP로 부릅니다(TRSS-261).

| ProductApi | 요청 | 없을 때·실패 |
| --- | --- | --- |
| `getOptions` | `GET /internal/v1/products/options?optionIds=1,2,3` | 없는 ID는 빠짐, `optionIds`가 없으면 빈 목록. 중복 제외 200개를 넘으면 400 `400-VALIDATION_FAILED` |

- 응답은 `RsData`이고 성공은 `200-1`, `data`에 `OptionSnapshotDto` 목록을 담습니다. 삭제된 상품·옵션도 포함합니다(`saleable = false`, 대표 이미지가 있으면 `thumbnailUrl`도 옴).
- 부르는 쪽: delivery-service `delivery.client.MarketProductApiClient`(`ProductImagePort`, `custom.market.baseUrl` = `MARKET_SERVICE_URL`, 기본 `http://localhost:8082`, 접속 1초·응답 2초). 물류 목록·상세·송장 등록·수정 응답의 상품 대표 이미지에 쓰고, 200개 단위로 나눠 부릅니다.
- 부르는 쪽(물류 응답)은 실패해도 200으로 응답하고 항목의 `thumbnailUrl`을 null로 둡니다.
- Swagger에는 나오지 않습니다(`@Hidden`). 호출할 때는 `X-Internal-Key` 헤더가 필요합니다 → [내부 API 공통 규칙](#내부-api-공통-규칙)

### DTO

```java
package com.backend.shared.product.dto;

/**
 * 옵션 하나의 현재 정보. saleable = 상품 ON_SALE + 옵션 삭제 안 됨 + 카테고리 사용 중 (재고와는 무관).
 * 재고 수량·본인 상품·결제 대기 주문 여부는 주문 컨텍스트가 따로 판단한다.
 * unsaleableReason은 saleable이면 null, 여러 개에 해당하면 enum 선언 순서가 우선순위다.
 * optionName은 옵션 값을 " / "로 이은 표시 이름(예: "흰색 / M", 최대 48자)이고, 옵션이 없는 상품이면 "기본".
 * price는 옵션 하나의 단가 = (할인 중이면 할인가, 아니면 상품 가격) + 옵션 추가금이다.
 * 주문 항목 단가·상품 쿠폰 할인 기준(lineAmount)·정산도 이 값이다.
 * listPrice는 정가 단가 = 상품 가격 + 옵션 추가금이다. 화면 표시용이고, 할인 중이 아니면 price와 같다.
 * discountRate는 할인율(%, 소수점 내림)이고 할인 중이 아니면 null이다. 상품 가격 기준이라 옵션 추가금과 무관하게 상품 상세의 할인율과 같다.
 * 화면 표시용이며 (listPrice - price)로 다시 계산하지 않는다(추가금이 있으면 값이 달라진다).
 * thumbnailUrl은 대표 이미지가 없으면 null.
 */
public record OptionSnapshotDto(Long optionId, Long productId, Long sellerId, String productName, String optionName,
                                long price, long listPrice, Integer discountRate, int stock, boolean saleable,
                                ProductUnsaleableReason unsaleableReason, String thumbnailUrl) {
}

/** 주문 항목 한 줄. orderId·orderItemId는 재고 이력에 남고 중복 처리를 막는 기준이 된다. quantity는 1 이상 */
public record StockChangeDto(Long orderId, Long orderItemId, Long optionId, int quantity) { }

/** 품절은 판매 불가 이유가 아니다 (재고는 stock으로 판단) */
public enum ProductUnsaleableReason { PRODUCT_DELETED, OPTION_DELETED, PRODUCT_STOPPED, CATEGORY_INACTIVE }
public enum StockRestoreReason { ORDER_RESTORE, RETURN_RESTORE }
```

- 확정(10/10): 주문과 주문 항목을 먼저 저장해 `orderItemId`를 만든 뒤, 같은 트랜잭션에서 `deductStock`을 호출합니다.
- 잠금 순서는 항상 **상품 → 주문(→ 옵션 재고)**입니다. 상품 삭제·중지는 상품을 잠근 뒤 `cancelPendingOrders`로 주문을 잠그므로, 주문을 먼저 잠그는 흐름은 `lockProductsForOrderChange`로 상품을 먼저 잠급니다.
- 구현 상태: 모든 메서드가 `ProductApiAdapter`에 구현되어 있습니다.

### 상품 옵션

상품은 옵션 이름(예: 색상·사이즈)을 최대 3개 갖고, 옵션 하나는 값의 조합 한 줄(SKU)입니다. 옵션이 없는 상품은 값 없는 옵션 1개를 갖습니다.
장바구니·주문은 옵션 ID 단위로 담고, 재고와 단가도 옵션마다 따로 있습니다.

| 항목 | 규칙 |
| --- | --- |
| 옵션 이름 | 0~3개, 각각 1~20자, 서로 달라야 한다. 등록 뒤에는 바꾸지 않는다 |
| 옵션 값 | 옵션 이름마다 하나씩, 각각 1~14자. 같은 조합은 한 상품에 한 번만 (409 `OPTION_DUPLICATED`) |
| 조합 수 | 한 상품에 삭제되지 않은 조합 최대 100개 |
| 표시 이름 | 값을 " / "로 잇는다(예: "흰색 / M", 최대 48자). 옵션이 없는 상품은 "기본". 주문·리뷰의 `option_name`(varchar(50))에 그대로 들어간다 |
| 단가 | 상품 가격(할인 중이면 할인가) + 옵션 추가금(0 이상). 판매자는 추가금만 바꿀 수 있고, 값은 바꿀 수 없다(다른 조합은 삭제 후 추가) |
| 삭제 | 소프트 삭제. 마지막 남은 옵션은 지울 수 없다 (409 `LAST_OPTION_NOT_DELETABLE`) |

- 판매자가 옵션을 지우면 그 옵션은 `saleable = false`(`OPTION_DELETED`)가 된다. `cancelPendingOrders`는 상품 단위라 옵션 삭제로는 부르지 않는다.
- 지운 옵션이 담긴 결제 대기 주문은 결제 때 `getOptions`로 판매 가능 여부를 다시 보므로 결제되지 않고(409 `PRODUCT_NOT_ON_SALE`), 구매자 취소나 결제 만료로 취소될 때 재고가 복원된다(ORDER_RESTORE는 삭제된 옵션도 재고를 늘린다).

### 상품 할인

판매자가 상품에 기간 할인(할인가, 시작·끝 시각)을 겁니다. 할인은 상품 가격에만 적용하고, 옵션 추가금은 할인한 가격에 그대로 더합니다.
할인이 진행 중인지는 조회 시각으로 계산하므로(스케줄러 없음), `getOptions`를 부르는 시각의 할인이 price에 들어갑니다.

| 항목 | 규칙 |
| --- | --- |
| 할인가 | 정가의 10~99% (할인율 1~90%). 할인율은 소수점 내림으로 보여 준다 |
| 기간 | 시작은 지금 이후, 끝은 시작 뒤 1년 이내. 시작 이상·끝 미만이 할인 기간이다 |
| 겹침 | 한 상품의 할인 기간은 겹칠 수 없다 (409 `DISCOUNT_PERIOD_OVERLAPPED`) |
| 변경 | 시작 전은 할인가·시작·끝을 바꾸거나 삭제할 수 있다. 진행 중은 끝 시각만 바꾸거나(연장·단축) 지금 종료할 수 있다. 끝난 할인은 이력으로 남는다 |
| 정가 수정 | 진행 중이거나 예약된 할인이 있으면 상품 가격을 바꿀 수 없다 (409 `PRICE_LOCKED_BY_DISCOUNT`) |

- 할인이 시작되거나 끝나는 순간 장바구니에서 본 금액과 주문 생성 때 금액이 달라지면 주문 생성은 409 `PRICE_CHANGED`다. 잘못된 가격으로 주문되지 않게 하려는 의도된 동작이다.
- 주문 금액은 주문 생성 때의 스냅샷으로 고정된다. 할인 중에 만든 주문은 할인이 끝난 뒤 결제해도 할인가다.
- 주문 컨텍스트(금액과 무관, 표시용, TRSS-222): 주문 항목에 정가 단가(`list_unit_price`)와 할인율(`discount_rate`)을 주문 때 스냅샷으로 저장한다(V14). 장바구니 항목은 현재 스냅샷의, 주문 상세 항목은 저장된 `listPrice`·`discountRate`를 그대로 응답한다. 할인 기능 전 주문은 `listPrice = unitPrice`, `discountRate = null`이다. `OptionSnapshotDto` 호환 생성자는 지웠다.

### 받는 이벤트

| 이벤트 | 처리 | 멱등 기준 |
| --- | --- | --- |
| `SellerSanctionedEvent` | `stopAllBySeller(sellerId, reason)`와 같은 처리 | `processed_event`(eventId, `market-product-seller`), 판매 중 상품만 바꾸므로 다시 와도 결과가 같다 |
| `SellerWithdrawalRequestedEvent` | `stopAllBySeller(sellerId, reason)`와 같은 처리 | 위와 같음 |
| `SellerWithdrawalRejectedEvent` | `resumeAllBySeller(sellerId)`와 같은 처리 | `processed_event`(eventId, `market-product-seller`), 철회로 중지된 상품만 바꾸므로 다시 와도 결과가 같다 |

- Kafka 토픽 `seller.events`를 market-service의 `ProductSellerEventListener`(`@KafkaListener` groupId `market`)로 받는다. 한 메시지를 한 트랜잭션에서 처리하고, 실패하면 재시도 후 `seller.events-dlt`로 보낸다.
- `SellerBusinessNameChangedEvent`는 market 검색 색인 컨슈머(groupId `market-search-index`, engine=es일 때)가 같은 토픽으로 받는다. 메시지 키가 판매자 ID라 봉투에서 `sellerId`를 읽고, 그 판매자의 판매 중 상품을 다시 색인한다(검색 색인 문서에 상호명이 있다, TRSS-331). `ProductSellerEventListener`·쿠폰·정산 컨슈머는 eventType 헤더로 거른다.
- 검색 색인 문서의 상호명은 색인할 때 `MemberApi.getSellers`로 가져온다. 회원 서비스가 실패하면 상호명 없이 색인하고(판매 중지·품절·가격 반영이 막히지 않게), 복구 스케줄러가 5분마다 상호명이 빠진 문서를 찾아 다시 색인해 채운다.
- 회원은 `stopAllBySeller`·`resumeAllBySeller`를 부르지 않는다(TRSS-160). 두 메서드는 market 안에서 컨슈머가 쓴다.

### 발행하는 이벤트

```java
package com.backend.shared.product.event;

/** 구매자가 상품을 찜함. 이미 찜한 상품을 다시 찜하면 발행하지 않는다 */
public record WishAddedEvent(Long memberId, Long productId) implements DomainEvent { }   // aggregateType PRODUCT, aggregateId productId

/** 구매자가 찜을 해제함. 찜하지 않은 상품을 해제하면 발행하지 않는다 */
public record WishRemovedEvent(Long memberId, Long productId) implements DomainEvent { }  // aggregateType PRODUCT, aggregateId productId

/** 상품의 내용·판매 상태·품절 여부·할인·카테고리가 바뀜. 바뀐 부분이 아니라 상품의 지금 상태 전체를 담는다 */
public record ProductChangedEvent(
        Long productId, String name, String description,
        String categoryName,      // 소분류 이름
        boolean beauty,           // 뷰티 대분류 상품인지
        String ingredientsText,   // 전성분 원문. 뷰티가 아니거나 아직 안 넣었으면 null
        long price,               // 정가
        String thumbnailUrl,      // 없으면 null
        boolean onSale,           // 판매 중이고 카테고리 사용 중. 삭제·판매 중지면 false
        Boolean soldOut           // 삭제되지 않은 옵션의 재고가 모두 0인지. 이 필드 전 메시지면 null (나중에 더한 필드는 래퍼 타입)
) implements DomainEvent { }      // aggregateType PRODUCT, aggregateId productId
```

- 찜 추가·해제와 같은 트랜잭션에서 outbox에 저장하고, 릴레이가 Kafka 토픽 `product.events`로 발행한다(키 `productId`, 같은 상품의 찜 이벤트는 순서대로 전달된다).
- 받는 쪽은 market 검색 색인 컨슈머다. 키(productId)로 찜 수를 다시 세어 색인한다(TRSS-290). AI 추천은 헤더로 거른다.
- market 추천 컨슈머(groupId `market-recommend`)도 받아 `memberId`의 개인화 추천을 다시 계산한다(TRSS-308).
- `ProductChangedEvent`는 상품 등록·수정·삭제, 관리자 판매 중지, 판매 중지 요청 승인, 판매자 철회·제재로 인한 전체 중지와 철회 거절·제재 해지로 인한 재개,
  품절 여부가 바뀐 재고 차감·복원·조정, 옵션 추가·삭제, 할인 등록·수정·삭제·지금 종료, 소분류 이름·사용 여부 변경 때 같은 트랜잭션에서 outbox에 저장한다.
  할인이 시작·종료되는 시각에는 할인 경계 스케줄러가 발행한다(TRSS-279).
  받는 쪽은 AI 추천(ai-service, TRSS-243)과 market 검색 색인(groupId `market-search-index`, 메시지 키로 DB를 다시 읽어 색인)이다.
  AI 추천은 같은 상품의 더 오래된 이벤트(봉투 `occurredAt`)는 버린다.
- `ProductMetricsChangedEvent(Long productId)`: 상품의 판매량이 바뀌었을 수 있음(결제·취소·반품, 최근 30일 집계 구간 밖으로 나간 판매). 판매 지표 스케줄러가 1분마다 찾아 발행한다(TRSS-290).
  상품 내용은 그대로라 AI 추천이 받는 `ProductChangedEvent`와 나눴다. 받는 쪽은 market 검색 색인 컨슈머뿐이고, AI 컨슈머는 eventType 헤더로 거른다.
  관리자 API `POST /api/v1/admin/products/change-events`가 판매 중인 상품 전체를 다시 발행한다(AI 서비스를 처음 띄울 때).

## 3.1 review

리뷰는 market-service 안의 별도 컨텍스트(`boundedContext/review`)다. 상품 컨텍스트에 리뷰 통계(`ReviewApi`)를 제공하고, 아래 공유 Api를 부른다.

```java
package com.backend.shared.review.out;

public interface ReviewApi {

    /** 상품별 리뷰 수·별점 합(삭제한 리뷰 제외). 리뷰가 없는 상품은 키가 없다. 상품 목록·상세의 리뷰 수·평균 별점과 검색 색인의 별점 점수 계산용 (TRSS-290) */
    Map<Long, ProductRatingStatsDto> getRatingStats(Collection<Long> productIds);

    /** 회원이 쓴 리뷰의 별점(삭제한 리뷰 제외, 최근 순). 개인화 추천의 기준 상품(4점 이상)과 뺄 상품(2점 이하)을 고를 때 쓴다 (TRSS-308) */
    List<MemberRatingDto> getMemberRatings(Long memberId);
}

// com.backend.shared.review.dto
public record MemberRatingDto(Long productId, int rating, LocalDateTime ratedAt) { }   // ratedAt: 리뷰 수정 시각

public record ProductRatingStatsDto(Long productId, long reviewCount, long ratingSum) {
    static ProductRatingStatsDto empty(Long productId);   // 리뷰 없음
    double averageRating();                               // 소수 첫째 자리 반올림, 리뷰가 없으면 0.0 (리뷰 평점 요약과 같은 값)
}
```

| 부르는 Api | 용도 |
| --- | --- |
| `OrderApi.findConfirmedOrderItem(buyerId, orderItemId)` | 리뷰 작성 자격 확인(본인 주문 + 구매 확정). 상품·옵션 ID와 주문 시점 옵션명도 여기서 가져온다 (TRSS-177) |
| `MemberApi.getMember(memberId)` | 작성자 이름(가운데 글자를 가려 리뷰에 저장)과 탈퇴 여부 |
| `ProductApi.getOptions(optionIds)` | 내 리뷰 목록의 상품명·대표 이미지 |

### 발행하는 이벤트

```java
package com.backend.shared.review.event;

/** 구매자가 리뷰를 작성함 */
public record ReviewCreatedEvent(Long reviewId, Long productId, int rating, Long memberId) implements DomainEvent { }                 // aggregateType REVIEW, aggregateId productId

/** 구매자가 리뷰를 수정함. 별점이 그대로여도(내용만 바뀌어도) 발행한다 */
public record ReviewModifiedEvent(Long reviewId, Long productId, int oldRating, int rating, Long memberId) implements DomainEvent { }  // aggregateType REVIEW, aggregateId productId

/** 구매자가 리뷰를 삭제함(소프트 삭제). rating은 삭제 직전 별점 */
public record ReviewDeletedEvent(Long reviewId, Long productId, int rating, Long memberId) implements DomainEvent { }                 // aggregateType REVIEW, aggregateId productId
```

- 리뷰 작성·수정·삭제와 같은 트랜잭션에서 outbox에 저장하고, 릴레이가 Kafka 토픽 `review.events`로 발행한다(키 `productId`, 같은 상품의 리뷰 이벤트는 순서대로 전달된다).
- 별점을 함께 실어서, 받는 쪽은 리뷰를 다시 조회하지 않고 상품별 평점 합계·리뷰 수를 증감할 수 있다.
- `memberId`는 리뷰를 쓴 회원이다(TRSS-308에서 추가, 래퍼 타입). 이 필드를 더하기 전에 발행한 메시지는 null이다.
- 받는 쪽
  - market 검색 색인 컨슈머(groupId `market-search-index`): 별점 증감 값은 쓰지 않고 키(productId)로 리뷰 통계를 다시 읽어 색인한다(TRSS-290).
  - market 추천 컨슈머(groupId `market-recommend`): `memberId`의 개인화 추천을 다시 계산한다. `memberId`가 null인 예전 메시지는 건너뛴다(TRSS-308).

## 4. order

### OrderApi

```java
package com.backend.shared.order.out;

public interface OrderApi {

    /**
     * 상품이 들어 있는 결제 대기(CREATED·PAYMENT_FAILED) 주문을 모두 취소하고 재고를 복원한다.
     * 결제 중(PAYING) 주문은 취소하지 않는다: 결제 시작 때 판매 가능을 확인했으므로 결제되면 PAID로 둔다.
     * 상품 삭제·판매 중지(관리자 즉시 중지·중지 요청 승인)·판매자 철회·제재 때 상품 컨텍스트가 상품을 잠근 뒤 호출한다. 호출한 쪽 트랜잭션에 참여한다.
     * 취소한 주문 수를 반환한다.
     */
    int cancelPendingOrders(Collection<Long> productIds, OrderCancelType cancelType);

    /** 구매자로서 진행 중인 주문(CREATED·PAYING·PAYMENT_FAILED·PAID·RETURN_REQUESTED)이나 반품 승인됐는데 환불 전인 주문(RETURNED, refunded_at 없음)이 있는지. 회원 탈퇴 검사용 */
    boolean hasActiveOrders(Long buyerId);

    /**
     * 판매자 상품이 들어간 진행 중인 주문(CREATED·PAYING·PAYMENT_FAILED·PAID·RETURN_REQUESTED)이 있는지.
     * 판매자 철회 승인·회원 탈퇴 검사용. 있으면 회원 컨텍스트가 409로 승인·탈퇴를 거절한다.
     */
    boolean hasActiveSales(Long sellerId);

    /**
     * 내 장바구니 항목들의 현재 상품 금액 합계 (판매 가능한 항목의 현재 가격 x 수량, 배송비 제외).
     * 쿠폰 컨텍스트가 사용 가능한 장바구니 쿠폰의 예상 할인액을 보여 줄 때 CouponOrderPort를 거쳐 부른다.
     * 판매 중지, 삭제된 옵션은 0원으로 센다. 내 장바구니 항목이 아니면 404-CART_ITEM_NOT_FOUND.
     */
    long getCartItemsAmount(Long buyerId, Collection<Long> cartItemIds);

    /**
     * 내 장바구니 항목들의 현재 가격 정보 (항목마다 상품, 단가, 수량, 판매 가능 여부). 보낸 순서대로, 중복 ID는 한 번만 준다.
     * 쿠폰 컨텍스트가 상품 쿠폰 코드를 검증할 때 CouponOrderPort를 거쳐 부른다.
     * 판매할 수 없는 항목은 saleable = false, 단가 0원. 내 장바구니 항목이 아니면 404-CART_ITEM_NOT_FOUND.
     */
    List<CartLineDto> getCartLines(Long buyerId, Collection<Long> cartItemIds);

    /**
     * 상품별 paidSince 이후 결제 수량. 판매량에 세는 상태(PAID·CONFIRMED·RETURN_REQUESTED)만 세므로 결제 뒤 취소·반품된 주문은 빠진다.
     * 판매가 없는 상품은 키가 없다. 상품 컨텍스트가 검색 색인의 판매량(최근 30일)을 계산할 때 쓴다 (TRSS-290)
     */
    Map<Long, Long> getSalesQuantities(Collection<Long> productIds, LocalDateTime paidSince);

    /** (from, to]에 결제·취소·반품(PAID·CANCELED·RETURNED)으로 상태가 바뀐 주문의 상품 ID. 판매 지표 스케줄러용 */
    List<Long> findProductIdsWithSalesChanged(LocalDateTime from, LocalDateTime to);

    /** (from, to]에 결제한(판매량에 세는) 주문의 상품 ID. 판매량 집계 구간 밖으로 나가는 판매를 찾을 때 쓴다 */
    List<Long> findProductIdsPaidBetween(LocalDateTime from, LocalDateTime to);

    /** 구매자가 paidSince 이후 결제한 상품(판매량에 세는 상태만), 상품마다 가장 최근 결제 시각으로 묶어 최근 순 최대 limit개. 개인화 추천 기준 상품 (TRSS-308) */
    List<PurchasedProductDto> getRecentPurchases(Long buyerId, LocalDateTime paidSince, int limit);

    /** 구매자 장바구니의 옵션과 담은 시각(최근 순). 장바구니는 옵션 단위라 상품은 상품 컨텍스트가 옵션으로 찾는다. 개인화 추천 기준 상품 (TRSS-308) */
    List<CartOptionDto> getCartOptions(Long buyerId);
}

// com.backend.shared.order.dto
public record PurchasedProductDto(Long productId, LocalDateTime paidAt) { }
public record CartOptionDto(Long optionId, LocalDateTime addedAt) { }
```

### CouponApi (쿠폰 컨텍스트, market-service)

```java
public interface CouponApi {
    /**
     * 판매를 멈춘(판매 중지, 삭제) 상품의 상품 쿠폰을 모두 중지한다 (중지 주체 SYSTEM).
     * 상품 컨텍스트가 ProductSaleEndSupport에서 상품 상태를 바꾼 트랜잭션 안에서 ProductCouponPort를 거쳐 부른다.
     * 아직 끝나지 않은 운영 중 쿠폰만 중지하고, 중지된 쿠폰은 상품이 다시 판매 중이 돼도 되살리지 않는다.
     */
    int stopProductCouponsByProducts(Collection<Long> productIds, String reason);

    // 아래 넷은 주문 컨텍스트가 OrderCouponPort를 거쳐 부른다

    /** 주문 생성(재고 차감 뒤): 상품 쿠폰을 주문 항목에 예약하고 그 항목의 할인액을 돌려준다. 한 주문에 1개 */
    long reserveProductCoupon(Long memberId, String code, Long orderId, Long orderItemId, Long productId, long lineAmount,
                              LocalDateTime now);

    /** 주문 생성(상품 쿠폰 다음): 장바구니 쿠폰을 예약하고 할인액을 돌려준다. orderAmount는 상품 쿠폰 할인 후 상품 금액 합계(배송비 제외) */
    long reserveCartCoupon(Long memberId, Long couponId, Long orderId, long orderAmount, LocalDateTime now);

    /** 결제 시작(결제 요청 전): 예약 쿠폰을 지금 쓸 수 있는지 확인. 예약 뒤 중지, 기간 종료면 409-COUPON_NOT_ACTIVE (돈이 움직이기 전) */
    void validateReservedCouponsUsable(Long orderId, LocalDateTime now);

    /** 결제 성공 반영(주문 PAID와 같은 Tx): 예약 쿠폰 사용 처리. 쿠폰 상태·기간은 보지 않는다(그사이 중지돼도 예약이 할인을 보장) */
    int useReservedCoupons(Long orderId, LocalDateTime now);

    /** 결제 전 취소, 만료(OrderCancelSupport): 예약 쿠폰 해제와 수량 복원. 다시 불러도 결과가 같다 */
    int releaseReservedCoupons(Long orderId, LocalDateTime now);
}
```

- 상품 금액은 옵션 단가(`OptionSnapshotDto.price`) x 수량이다. 상품 쿠폰도 추가금을 포함한 이 금액에 할인을 적용한다.
- 할인은 상품 할인 → 상품 쿠폰 → 장바구니 쿠폰 순서로 적용한다. 할인 상품에도 쿠폰을 쓸 수 있고, 상품 쿠폰 할인액이 할인가 기준 상품 금액 이상이면 400 `COUPON_NOT_APPLICABLE`이다.
- 주문 금액은 상품 금액 합계 - 상품 쿠폰 할인 - 장바구니 쿠폰 할인이다. 결제 요청(PayCommand.amount)은 이 결제 금액(payAmount)이다.
  배송비는 배송비 작업에서 상품 쿠폰 할인 후 금액을 기준으로 더한다(장바구니 쿠폰 기준 금액은 배송비 제외라 바뀌지 않는다).
- 장바구니 쿠폰 할인은 플랫폼 부담이다. 주문의 장바구니 쿠폰 할인액을 PayCommand.couponSubsidyAmount로 보내고, 결제가 플랫폼 지갑에서 홀딩으로 채운다.
  상품 쿠폰 할인은 판매자 부담이라 지원금이 없고, 정산이 할인 후 금액으로 지급한다.
- 반품해도 사용한 쿠폰(상품·장바구니)은 복원하지 않는다. 구매자는 낸 금액(PayCommand.amount) 안에서만 돌려받고
  (상품불량·기타는 전액, 단순변심은 처음 배송비를 뺀 상품값),
  장바구니 쿠폰 지원금은 반품 환불 때 홀딩에서 관리자 지갑으로 돌려받는다(COUPON_SUBSIDY_CANCEL).
  결제 전 취소·만료는 지금처럼 예약 쿠폰을 해제해 복원한다(releaseReservedCoupons).
- 쿠폰은 설정 custom.order.couponEnabled(환경변수 ORDER_COUPON_ENABLED, 기본 true)가 켜져 있을 때만 주문에 반영한다.
  결제의 장바구니 쿠폰 지원금(COUPON_SUBSIDY), 정산의 상품 쿠폰 할인 후 금액, 반품 환불의 지원금 회수가 모두 지원해 기본으로 켠다.
  문제가 생기면 ORDER_COUPON_ENABLED=false로 끈다. 꺼져 있을 때 쿠폰을 보내면 409-ORDER_COUPON_NOT_AVAILABLE이라 결제 금액은 상품 금액 합계와 같다.

- 쿠폰 컨텍스트가 부르는 다른 기능은 모두 쿠폰 쪽 포트를 거친다: CouponSellerPort(MemberApi), CouponProductPort(ProductApi), CouponOrderPort(OrderApi).
- 상품 쿠폰은 진행 중인 협약의 인플루언서용으로, 판매자가 판매 중인 내 상품 1개를 골라 발행한다. 인플루언서 pick 조건은 두지 않는다(pick 기능이 생기면 발행 조건과 InfluencerPickPort를 다시 추가한다).
- 발행 폼의 상품 목록은 판매자 상품 목록 API(GET /api/v1/seller/products?status=ON_SALE)를 쓴다. 협약 pick 상품 목록 API(GET /api/v1/seller/partnerships/{id}/picked-products)는 없앴다.
- influencerId는 인플루언서 테이블 ID(influencer.id)이고 회원 ID가 아니다.
- 판매자 제재(SellerSanctionedEvent)는 쿠폰 컨슈머가 groupId market-coupon으로 따로 받아 협약을 종료한다.

### 이벤트

```java
package com.backend.shared.order.event;

/** 결제 완료. 배송지가 있는 주문만 발행한다(새 주문은 생성 요청에서 배송지가 필수라 항상 있다. 배송지 없이 만든 예전 주문만 빠진다). 배송 컨텍스트가 배송(READY)을 만든다. aggregate: ORDER / orderId */
public record OrderPaidEvent(Long orderId, String orderNumber, Long buyerId, LocalDateTime paidAt,
                             ShippingAddressDto shippingAddress, List<PaidOrderItemDto> items) implements DomainEvent { }

/** 구매 확정. 정산 컨텍스트가 주문 항목별 정산 후보를 만든다. aggregate: ORDER / orderId */
public record OrderConfirmedEvent(Long orderId, Long buyerId, LocalDateTime confirmedAt,
                                  ConfirmType confirmType, List<ConfirmedOrderItemDto> items) implements DomainEvent { }

/**
 * 반품 승인. 관리자 승인 트랜잭션에서 재고 복원과 함께 발행한다. aggregate: ORDER / orderId → Kafka order.events
 * 결제 컨텍스트가 받아 반품 사유에 따라 환불하고, 장바구니 쿠폰 지원금을 플랫폼 지갑으로 회수한다.
 *   상품불량·기타·사유 없음: 결제 금액 전액(배송비 포함). 단순변심: 구매자가 반품 회수 택배비를 결제하면 상품값만(처음 배송비 제외).
 * refundAmount = 주문의 pay_amount(배송비 포함 결제 금액 전액, 쿠폰 할인분은 돌려주지 않음).
 *   결제는 금액 내역(배송비·지원금)을 자기 결제 건에 저장된 값으로 나누고, refundAmount는 대조용으로만 쓴다.
 * returnReasonCode: 구매자가 반품 요청 때 고른 사유. 사유를 싣기 전에 발행된 이벤트면 null이고 전액 환불한다.
 *   사유 없이 부르던 4인자 생성자는 사유 null로 남겨 둔다.
 */
public record OrderReturnedEvent(Long orderId, Long buyerId, long refundAmount, LocalDateTime returnedAt,
                                 ReturnReasonCode returnReasonCode) implements DomainEvent { }
```

- market 추천 컨슈머(groupId `market-recommend`)가 `OrderPaidEvent`·`OrderConfirmedEvent`·`OrderReturnedEvent`를 받아 `buyerId`의 개인화 추천을 다시 계산한다(TRSS-308). 취소 이벤트는 없어, 취소는 저장한 추천이 한 시간 지나 다시 계산될 때 반영된다.

### DTO

```java
package com.backend.shared.order.dto;

/**
 * 구매 확정 항목. lineAmount = 할인 전 항목 금액(unitPrice × quantity).
 * productCouponDiscount = 항목 금액에서 빠진 상품 쿠폰 할인액(주문 항목 discount_amount, 없으면 0).
 *   할인 후 금액(lineAmount - productCouponDiscount)으로 무엇을 계산할지는 정산 정책에 따른다.
 *   장바구니 쿠폰 할인은 결제 시 플랫폼 지원금(COUPON_SUBSIDY)으로 홀딩을 보전하므로 이 필드에 넣지 않는다.
 *   하위 호환: 이 필드가 없는 이전 형식 메시지(outbox PENDING·Kafka 보관분)는 0으로 읽는다.
 *   (그래서 Long으로 받고 값이 없으면 0으로 본다. long으로 하면 값이 빠진 JSON을 읽을 때 오류가 난다.)
 *   6개 인자 생성자는 할인 0으로 채운다.
 * productName, optionName = 주문할 때의 상품명·옵션명 스냅샷(주문 항목 product_name, option_name). 정산 내역·엑셀 표시용.
 *   하위 호환: 이 필드가 없는 이전 형식 메시지는 null로 읽는다. 6·7개 인자 생성자는 이름을 null로 채운다.
 */
public record ConfirmedOrderItemDto(Long orderItemId, Long productId, Long sellerId,
                                    long unitPrice, int quantity, long lineAmount,
                                    Long productCouponDiscount, String productName, String optionName) { }

/** 주문 배송지 스냅샷 (주문할 때 받은 값 그대로). 로그에 남기지 않는다 */
public record ShippingAddressDto(String receiverName, String receiverPhone, String zipCode,
                                 String address, String addressDetail, String requestMessage) { }

/** 결제된 주문 항목 (배송 피킹 목록용). 주문 항목에 저장된 스냅샷 값 */
public record PaidOrderItemDto(Long orderItemId, Long productId, Long optionId, Long sellerId, String sellerName,
                               String productName, String optionName, int quantity) { }

public enum OrderCancelType { USER, EXPIRED, PRODUCT_STOPPED, SELLER_SANCTIONED }
public enum ConfirmType { MANUAL, AUTO, RETURN_REJECTED }
/** 반품 사유. 주문(반품 요청)과 결제(반품 환불 금액)가 같이 쓴다 */
public enum ReturnReasonCode { CHANGE_OF_MIND, DEFECTIVE, OTHER }
```

## 5. payment

### PaymentApi

```java
package com.backend.shared.payment.out;

public interface PaymentApi {

    /** 잔액과 홀딩 중 금액. 지갑이 없으면 404 WALLET_NOT_FOUND */
    WalletSummaryDto getWallet(Long memberId);

    /** 결과가 정해지지 않은 충전 건(READY·PROCESSING·CANCELING·METHOD_CANCELING)이 있는지. 회원 탈퇴 검사용 (반영 전이거나 취소가 거절되면 잔액이 다시 들어올 수 있음) */
    boolean hasPendingTopups(Long memberId);

    /**
     * 주문 결제: 구매자 지갑을 잠그고 지갑 → 홀딩, 원장 PAYMENT 기록. HTTP(POST /internal/v1/payments/pay)라 호출한 쪽 트랜잭션에 참여하지 않는다.
     * 주문 쪽은 주문을 PAYING으로 커밋한 뒤 부르고, 결과를 받은 뒤 별도 트랜잭션으로 반영한다(9.1).
     * command.amount는 배송비를 포함한 결제 금액이다. 배송비(command.shippingFee)는 구매자 지갑 → 플랫폼 지갑(원장 SHIPPING_FEE)으로 옮기고,
     * 나머지(amount − shippingFee)는 홀딩에 넣는다. 장바구니 쿠폰 지원금(command.couponSubsidyAmount)은 플랫폼 지갑 → 홀딩(원장 COUPON_SUBSIDY).
     * 홀딩 금액 = amount − shippingFee + couponSubsidyAmount.
     * (배송비·지원금 처리는 결제 담당 구현 예정. 그 전까지는 amount 전체가 홀딩에 들어가고 두 필드는 쓰이지 않는다)
     * 잔액 부족·주문 만료는 예외가 아니라 결과로 반환한다. 실패 기록은 별도 트랜잭션(REQUIRES_NEW)으로 남긴다.
     * 주문 쪽은 결과가 PAID가 아니면 주문을 PAYMENT_FAILED로 커밋한 뒤 DomainException(402 INSUFFICIENT_BALANCE 등)을 던진다.
     * 같은 idempotencyKey로 다시 호출하면 처음 결과를 그대로 반환한다. 아직 처리하지 않은 키면 그때 처리한다(키 UNIQUE·지갑 잠금으로 직렬화).
     * 주문 쪽 재확인 스케줄러가 결과를 모르는 결제를 같은 키·같은 내용으로 다시 부르므로 이 동작에 기댄다.
     * PAID 결과에는 payment가 기록한 결제 완료 시각(paidAt)을 담고, 주문 쪽은 이 값을 주문의 paidAt으로 쓴다(없으면 반영 시각).
     * 오류: 4xx RsData는 주문 쪽이 "결제 없음"으로 보고 PAYMENT_FAILED로 반영한다(단 409 LOCK_CONFLICT·CONCURRENT_UPDATE·DATA_CONFLICT, 429는 결과 불명).
     *      401·403(내부 키 오류)은 주문 쪽 클라이언트가 503-INTERNAL_API_AUTH_FAILED + ERROR 로그로 바꾼다.
     *      첫 결제 요청이면 이 키로 결제한 적이 없으므로 PAYMENT_FAILED로 반영하고 503을 응답한다.
     *      재확인 스케줄러의 재요청이면 결과 불명으로 보고 PAYING으로 둔다(앞선 요청이 결제됐을 수 있다. 키가 고쳐지면 다음 재확인이 정리).
     *      접속 실패·응답 시간 초과·5xx는 결과 불명으로 보고 주문을 PAYING으로 둔다.
     */
    PayResultDto pay(PayCommand command);

    /**
     * 결제 취소(보상): 결제는 됐는데 주문에 반영할 수 없을 때 결제를 되돌린다. 멱등.
     * HTTP POST /internal/v1/payments/{idempotencyKey}/cancel
     */
    void cancelPayment(String idempotencyKey);

    /**
     * 주문의 결제 시도 목록 (주문 상세의 결제 내역). 오래된 순, 실패 시도 포함.
     * 내 주문인지는 주문 쪽에서 확인하고 호출한다. 결제 시도가 없으면 빈 목록을 반환한다.
     * 주문 쪽은 DB 트랜잭션 밖에서 부르고, 응답 대기는 결제 요청(readTimeoutMillis 5초)보다 짧은 queryReadTimeoutMillis(2초)를 쓴다.
     */
    List<PaymentAttemptDto> getPaymentAttempts(Long orderId);

    /**
     * 정산 지급: 홀딩 → 판매자 지갑(SETTLEMENT), 홀딩 → 관리자 지갑(FEE).
     * 주문별 홀딩의 settled_amount를 늘리고, 다 차면 SETTLED로 바꾼다.
     * idempotencyKey(SETTLEMENT-{settlementId})가 이미 처리됐으면 기존 결과를 반환한다.
     * HTTP라 호출한 쪽 트랜잭션에 참여하지 않는다. 4xx는 "돈이 움직이지 않은 거절", 5xx·응답 없음은 "결과 모름"이다.
     * 같은 키 요청이 겹쳐도 두 번째 요청이 오류 대신 기존 결과를 받도록, 결제 쪽은 홀딩을 잠근 뒤 지급 내역을 한 번 더 찾는다.
     * 오류: 404 WALLET_NOT_FOUND, 404 HOLDING_NOT_FOUND, 409 HOLDING_INSUFFICIENT, 409 TRANSFER_AMOUNT_MISMATCH
     */
    TransferResultDto transfer(SettlementTransferCommand command);

    /** 정산 지급 여부 조회 (재시도·대사용). 없으면 empty */
    Optional<TransferResultDto> findTransfer(String idempotencyKey);
}
```

`404-WALLET_NOT_FOUND`는 **그 회원의 지갑이 아직 없음**을 뜻합니다. 지갑은 `MemberSignedUpEvent`를 받아 만들므로 가입 직후 이벤트 처리 전이거나 처리에 실패했으면 없고, 물류 담당자(`LOGISTICS`)는 지갑을 만들지 않습니다. 이 응답을 쓰는 곳:

| 부르는 쪽 | 처리 |
| --- | --- |
| 회원 탈퇴 검사 (`GET /internal/v1/payments/wallets/{memberId}`) | 잔액 0으로 보고 통과 |
| 관리자 회원 상세 (같은 API) | `walletState: NOT_CREATED` |
| 정산 지급 (`transfer`) | 재시도 불가 사유(`SettlementPolicy`)라 바로 `MANUAL_REQUIRED` |

### DTO

```java
package com.backend.shared.payment.dto;

public record WalletSummaryDto(Long memberId, long balance, long heldAmount) { }

/**
 * amount: 구매자 결제 금액(배송비 포함, 쿠폰 할인 후), shippingFee: 그중 배송비(0 이상, amount 미만).
 * couponSubsidyAmount: 장바구니 쿠폰 지원금 = 주문의 장바구니 쿠폰 할인액(0 이상). 장바구니 쿠폰은 플랫폼 부담이다.
 * 결제는 배송비를 홀딩에 넣지 않고 플랫폼 지갑으로 옮기고(SHIPPING_FEE), 지원금을 플랫폼 지갑에서 홀딩으로 옮긴다(COUPON_SUBSIDY).
 * 홀딩 금액 = amount − shippingFee + couponSubsidyAmount. 정산은 이 홀딩에서 판매자·수수료를 지급한다(배송비는 정산 대상 아님).
 * 금액 불변식: 주문별 홀딩 금액 = 그 주문의 정산 판매가 합(항목 금액 − 상품 쿠폰 할인액의 합) = 판매자 지급액 합 + 수수료 합.
 *   상품 쿠폰 할인은 판매자 부담이라 홀딩과 정산 판매가 모두에서 빠지고, 장바구니 쿠폰 할인은 플랫폼 지원금으로 홀딩을 채워 정산 판매가에서 빠지지 않는다.
 * (두 필드의 처리는 결제 담당 구현 예정)
 * 두 필드 없이 부르던 6인자 생성자는 둘 다 0으로 남겨 둔다.
 * HTTP 본문에서 두 필드는 필수다. 값이 없으면 0을 보낸다. 빠지거나 null이면 400(Jackson 3 기본 설정).
 * 그래서 market과 payment는 같은 버전으로 함께 배포한다(옛 market이 새 payment를 부르면 400).
 */
public record PayCommand(Long orderId, String orderNumber, Long buyerId, long amount, long shippingFee,
                         long couponSubsidyAmount, LocalDateTime orderExpiresAt, String idempotencyKey) { }

/**
 * shortage: 잔액 부족일 때 모자란 금액, chargeAmount: 1만 원 단위로 올린 충전 권장 금액
 * paidAt: 결제 완료 시각(Payment.paidAt). PAID만 있고 그 밖의 상태·이전 버전 payment 응답은 null (TRSS-307)
 */
public record PayResultDto(Long paymentId, PayResultStatus status, long shortage, long chargeAmount, LocalDateTime paidAt) {
    public PayResultDto(Long paymentId, PayResultStatus status, long shortage, long chargeAmount) { this(..., null); }
    public boolean isPaid() { return status == PayResultStatus.PAID; }
}

public enum PayResultStatus { PAID, FAILED_INSUFFICIENT_BALANCE, FAILED_ORDER_EXPIRED }

/** 결제 시도 1건. paidAt은 PAID·REFUNDED만, refundedAt은 REFUNDED만 */
public record PaymentAttemptDto(Long paymentId, PaymentAttemptStatus status, long amount,
                                LocalDateTime attemptedAt, LocalDateTime paidAt, LocalDateTime refundedAt) { }

public enum PaymentAttemptStatus { PAID, REFUNDED, FAILED_INSUFFICIENT_BALANCE, FAILED_ORDER_EXPIRED, FAILED_SYSTEM_ERROR }

/** 정산서 1건 = 판매자 1명 × 1개월 */
public record SettlementTransferCommand(String idempotencyKey, Long settlementId, Long sellerMemberId,
                                        long payoutAmount, long feeAmount,
                                        List<SettledOrderAmountDto> orders) { }

/** amount = 그 주문에서 이 판매자 몫의 판매 금액 합(지급액 + 수수료) */
public record SettledOrderAmountDto(Long orderId, long amount) { }

public record TransferResultDto(String idempotencyKey, Long walletTransactionId,
                                long payoutAmount, long feeAmount, LocalDateTime transferredAt) { }
```

### 받는 이벤트

| 이벤트 | 처리 | 멱등 기준 |
| --- | --- | --- |
| `OrderReturnedEvent` | Kafka `order.events`(`PaymentOrderEventListener`, groupId `payment`, 리스너에 트랜잭션 없음). ① 환불 기록(`payment_refund`, REQUESTED) 저장 → ② 확인 후 환불 → ②가 실패하면 ③ `FAILED_사유` 기록을 겹치지 않는 트랜잭션으로 차례로 실행. 상품불량·기타·사유 없음은 바로 COMPLETED(원장 REFUND + SHIPPING_FEE_REFUND(배송비 > 0) + COUPON_SUBSIDY_CANCEL(지원금 > 0), 홀딩 RELEASED, 결제 REFUNDED). 단순변심은 `PICKUP_FEE_REQUIRED`로 두고, 구매자가 회수 택배비를 결제하면 COMPLETED(RETURN_PICKUP_FEE_PAYMENT·RETURN_PICKUP_FEE + REFUND(상품값) + COUPON_SUBSIDY_CANCEL). 금액 내역은 결제 건에 저장된 값을 쓰고 `refundAmount`는 대조용. 다시 해도 같은 실패(`FAILED_AMOUNT_MISMATCH`·`FAILED_HOLDING_SETTLED`·`FAILED_PAYMENT_NOT_PAID`·`FAILED_PLATFORM_BALANCE`)는 기록만 하고 사람이 확인. 일시 오류(`FAILED_SYSTEM_ERROR`)는 기록 뒤 예외를 다시 던져 Kafka 컨슈머 재시도(처음 + 4번)로 다시 처리하고, 5번 실패하면 멈춤 | `payment_refund.order_id` 유니크(주문당 환불 1건)로 다시 온 이벤트·다른 이벤트 ID를 같은 기록으로 모으고, 기록 행을 잠근 뒤 COMPLETED면 아무것도 하지 않음. 원장 `idempotency_key`(`REFUND-{환불ID}…`) 유니크로 한 번 더 막음. `processed_event`는 쓰지 않음(이벤트 ID로 건너뛰면 Kafka 재시도로 다시 온 메시지까지 건너뛰어 재시도가 안 됨) |
| `MemberSignedUpEvent` | 잔액 0인 지갑 생성 (Kafka `member.events`, `@KafkaListener` groupId `payment`, 가입과 별도 트랜잭션) | `processed_event`(eventId, `payment`)로 재전달을 거르고, 지갑이 이미 있으면 무시(`wallet.member_id` 유니크) |

- `OrderReturnedEvent` 배포 조건: `order.events`는 보관 기간을 따로 정하지 않아 브로커 기본값(7일)이 지나면 메시지가 지워진다. 반품 승인(market)이 먼저 배포되면 **7일 안에 payment 구독을 배포**하거나, 그 전까지 반품 승인을 열지 않는다. 그렇지 않으면 그 사이 반품 환불이 오류 없이 빠진다. payment 컨슈머는 `auto-offset-reset: earliest`라 배포 전에 쌓인 이벤트는 처음 구독할 때 받는다

- 시스템 오류는 결과가 아니라 HTTP 5xx·응답 없음으로 옵니다. 주문 쪽은 결과 불명으로 보고 주문을 PAYING으로 둔 뒤, 재확인 스케줄러가 같은 키로 `pay`를 다시 불러 PAID·PAYMENT_FAILED로 정리합니다(9.1).

## 6. settlement

정산은 `settlement-service`(포트 8084, DB 스키마 `settlement`)입니다. 다른 서비스와는 아래처럼 연결합니다.

| 방향 | 구현 | 동작 |
| --- | --- | --- |
| 정산 → 회원 (`getSeller`, `getSellers`, `findSellerByMemberId`) | `settlement-service`의 `MemberApiClient` (HTTP, `/internal/v1/sellers`) | 판매자 상태(제재 여부), 지급 대상 회원 ID, 상호명 조회. 회원 서비스가 응답하지 않으면 502로, 지급이라면 그 정산서는 실패로 기록되고 재시도 |
| 정산 → 결제 (`transfer`, `findTransfer`) | `settlement-service`의 `PaymentApiClient` (HTTP, `/internal/v1/payments/transfers`) | 4xx는 결제가 거절(정산서 실패 기록), 5xx와 응답 없음은 결과 모름(정산서 PROCESSING, 재확인 스케줄러가 `findTransfer`로 확정). 지급 내역이 없으면 `data: null` → `Optional.empty` |
| 주문 → 정산 (`OrderConfirmedEvent`) | 없음 (Kafka `order.events`) | 그대로 동작 |

정산 스케줄러는 기본으로 켜져 있습니다(`SETTLEMENT_SCHEDULING_ENABLED` 기본 true). 개발 서버의 재시도 간격은 120분입니다(`SETTLEMENT_RETRY_INTERVAL_MINUTES`).

정산 흐름은 수집, 사전 판별, 확정, 지급으로 나뉩니다.

| 단계 | 언제 | 하는 일 |
| --- | --- | --- |
| 수집 | 구매 확정 이벤트를 받을 때 | 정산 후보 생성(금액 검사, 원본 이벤트 ID `source_event_id` 기록) |
| 사전 판별 | 매월 1~9일 02:30 | 지난달 정산 대상을 판별 규칙으로 미리 판별해 이력만 남김(상태는 바꾸지 않음) |
| 확정 (월 정산 Job, 확정 Step 하나) | 매월 10일 00:00, 관리자 수동 실행 | 판매자마다 후보를 잠그고 그 후보로 판별 → READY 또는 HOLD 정산서, 지급 예정일(`payable_on`, 확정한 날 또는 다음 평일) |
| 지급 | 10분마다 지급 스케줄러 | 지급 예정일이 된 READY + 재시도 시각이 된 FAILED를 지급. 한 회차에 대상이 없을 때까지(최대 8분) 이어서 지급 |

관리자 수동 실행(`POST /api/v1/admin/settlements/runs`)은 실행할 수 있는지만 확인하고 202로 바로 돌아옵니다. 결과는 실행 기록(`GET /api/v1/admin/settlements/runs`)으로 봅니다.

### 관리자 실패 거래 공통 형식 (AdminFailureItem)

관리자 실패 거래 화면(A7)은 서비스마다 `GET /api/v1/admin/{서비스}/failures`를 불러 결과를 한 표로 합칩니다. 그래서 모든 서비스가 `common`의 `com.backend.shared.admin.dto.AdminFailureItem`으로 돌려줍니다.

- 공통 요청: `from`, `to`(yyyy-MM-dd, 포함, 한국 시간), `page`, `size`. 관리자(ADMIN)만
- 공통 응답: `RsData<PageResponse<AdminFailureItem>>`, `occurredAt` 내림차순(최신순). `totalElements`를 화면 탭 건수로 쓴다
- 대상 이름: 회원은 `AdminFailureItem.maskEmail(이메일)`, 판매자는 상호명

| 필드 | 뜻 |
| --- | --- |
| `type` | `AdminFailureType`: PAYMENT_FAILED, TOPUP_DELAYED, REFUND_FAILED, SETTLEMENT_MANUAL_REQUIRED, DELIVERY_TRACKING_FAILED |
| `occurredAt` | 실패가 생긴(또는 지금 상태가 된) 시각 |
| `targetType`, `targetId` | MEMBER(회원 ID) 또는 SELLER(판매자 ID) |
| `targetLabel` | 표시용 대상 이름. 모르면 null |
| `amount` | 금액(원). 없는 유형은 null |
| `reasonCode`, `reasonLabel` | 원인 코드와 표의 "원인" 문구 |
| `handlingCode`, `handlingLabel` | 처리 방법 코드와 표의 "처리" 문구 |
| `refId`, `orderId` | 원본 ID(결제, 충전, 환불, 정산서, 배송), 관련 주문 ID(없으면 null) |

| 유형 | 서비스 | 경로 | 상태 |
| --- | --- | --- | --- |
| SETTLEMENT_MANUAL_REQUIRED | settlement | `GET /api/v1/admin/settlements/failures` | 구현 |
| PAYMENT_FAILED, REFUND_FAILED | payment (결제, 반품 환불) | `GET /api/v1/admin/payments/failures` (type 없으면 둘 다) | 구현 |
| TOPUP_DELAYED | payment (충전) | `GET /api/v1/admin/topups/failures` | 구현 (TRSS-302) |
| DELIVERY_TRACKING_FAILED | delivery | `GET /api/v1/admin/deliveries/failures` | 구현 (TRSS-291) |

충전 확인 지연(payment 충전) 항목:

- 대상
  - 결과 확인 대기: `PROCESSING`·`CANCELING`·`METHOD_CANCELING`으로 결과 확인 대기 시간(`custom.payment.topupReconcileDelayMinutes`, 기본 5분)보다 오래 머문 충전 건
  - 실패: `FAILED_SYSTEM_ERROR`, `FAILED_AMOUNT_MISMATCH`
  - 사용자 취소(`FAILED_USER_CANCEL`)·만료(`FAILED_EXPIRED`)·카드 거절(`FAILED_PG_DECLINED`)·결제 수단 자동 취소(`FAILED_METHOD_NOT_ALLOWED`)는 정상 흐름이거나 이미 정리된 건이라 뺀다
- `occurredAt` = 지금 상태가 된 시각(`updatedAt`), `from`·`to`도 이 날짜로 거른다
- `targetType` = MEMBER, `targetId` = 회원 ID, `targetLabel` = 마스킹한 이메일
- `amount` = 충전 금액, `refId` = 충전 ID, `orderId` = null
- `reasonCode` = 충전 상태, `reasonLabel`
  - `PROCESSING` "PG 승인 결과 확인 중", `CANCELING` "PG 취소 결과 확인 중", `METHOD_CANCELING` "허용하지 않은 결제 수단 취소 결과 확인 중"
  - `FAILED_SYSTEM_ERROR` "시스템 오류", `FAILED_AMOUNT_MISMATCH` "금액 불일치". 저장된 실패 사유를 괄호로 붙인다(예: "시스템 오류 (PG 인증에 실패했습니다.)")
  - 수동 확인으로 표시된 건은 사유를 괄호로 붙인다(예: "PG 취소 결과 확인 중 (수동 확인: PG 취소 금액이 기록과 다름)"). 사유: "PG 취소 금액이 기록과 다름", "PG에 결제 없음", "일부만 취소됨", "PG 취소 재시도 모두 실패"
- `handlingCode`
  - 결과 확인 대기이고 오래 남은 건 경고 기준(`custom.payment.topupStuckWarnMinutes`, 기본 60분) 이하: `AUTO_RECHECK`("자동 확인 대기"). 결과 확인 스케줄러가 PG에 다시 확인한다
  - 결과 확인 대기이고 60분 초과, 또는 실패: `CHECK_REQUIRED`("확인 필요"). 60분은 오류 로그 알림이 나가는 기준과 같다
  - 수동 확인으로 표시된 건(대사가 자동으로 정할 수 없는 CANCELING·METHOD_CANCELING): 시간과 관계없이 `CHECK_REQUIRED`. 결과 확인 대기 5분 전이어도 목록에 나온다. 대사가 더 확인하지 않으므로 관리자가 정리한다

배송 추적 실패(delivery) 항목:

- 대상: 송장 등록 후 `custom.delivery.trackingFailureAfterDays`(기본 15일)가 지나도 배송 완료(DELIVERED)가 안 된 SHIPPED 배송. 정확히 15일째도 포함
- `occurredAt` = 송장 등록 시각(`shippedAt`), `from`·`to`도 이 날짜로 거른다
- `targetType` = SELLER, `targetId`·`targetLabel` = 배송 항목의 결제 시점 판매자 스냅샷(sellerId, 상호명). 회원 서비스는 부르지 않는다. 판매자가 여럿이면 첫 항목의 판매자와 "상호명 외 N곳"
- `amount` = null
- 원인은 택배사에서 받은 마지막 상태(`lastTrackingStatus`)로 나눈다
  - 상태가 없음: `TRACKING_NOT_FOUND`, "송장 등록 후 15일째 택배사 조회 결과 없음". 안전망이 매일 다시 조회해도 택배사에 없는 송장이라 송장 번호부터 확인한다(송장을 수정하면 상태가 비어 여기에 든다)
  - 상태가 있음: `STALLED_AFTER_SHIPPED`, "송장 등록 후 15일 지남 (택배사 상태: EXCEPTION)". 택배사 상태가 완료 전에서 멈췄다
- `handlingCode` = `LOGISTICS_CHECK`("물류 담당자 확인"), `refId` = 배송 ID, `orderId` = 주문 ID
- 웹훅·조회 실패 횟수는 저장하지 않는다. 원인은 이미 있는 택배사 상태로만 나눈다

### SettlementApi

```java
package com.backend.shared.settlement.out;

public interface SettlementApi {

    /**
     * 아직 지급되지 않은 정산 금액. 철회·탈퇴 검사용.
     * 구매 확정된 금액만 센다: READY·INCLUDED 정산 후보 중 PAID 정산서에 포함되지 않은 것.
     * 결제됐지만 아직 확정 전인 주문은 포함하지 않는다.
     */
    long getUnsettledAmount(Long sellerId);

    /** 관리자 제재: 이 판매자의 정산을 HOLD로 바꾸고, 이후 만들어지는 정산서도 HOLD로 둔다 */
    void holdSeller(Long sellerId, String reason);
}
```

### 내부 API (HTTP)

회원은 `SettlementApi.getUnsettledAmount` 대신 아래 내부 API를 HTTP로 부릅니다. 회원 쪽 클라이언트는 `MemberSettlementPort`의 HTTP 구현(`MemberSettlementHttpAdapter`)입니다([개발 가이드 6.2](palette_development_guide.md)).

| SettlementApi | 요청 | 없을 때·실패 |
| --- | --- | --- |
| `getUnsettledAmount` | `GET /internal/v1/settlements/sellers/{sellerId}/unsettled-amount` | 정산 후보가 없거나 없는 판매자면 200 + `data: 0` |

- 응답은 다른 API와 같은 `RsData`이고 성공은 `200-1`, `data`에 금액(원)을 담습니다.
- `holdSeller`는 HTTP로 열지 않습니다. 회원이 발행하는 `SellerSanctionedEvent`(sellerId, reason)를 정산 컨슈머(groupId `settlement`)가 `seller.events` 토픽으로 받아 보류합니다. 회원은 `holdSeller`를 부르지 않습니다(TRSS-161).
- Swagger에는 나오지 않습니다(`@Hidden`). 호출할 때는 `X-Internal-Key` 헤더가 필요합니다 → [내부 API 공통 규칙](#내부-api-공통-규칙)

### 받는 이벤트

| 이벤트 | 처리 | 멱등 기준 |
| --- | --- | --- |
| `OrderConfirmedEvent` | 주문 항목마다 정산 후보 생성(수수료 = 판매가 × 10 ÷ 100 버림, 정산월 = 확정일 KST 기준 월) | `processed_event`(eventId, `settlement`)로 재전달을 거르고, `settlement_candidate.order_item_id` 유니크로 한 번 더 막음 |
| `SellerSanctionedEvent` | `holdSeller(sellerId, reason)`와 같은 처리, 처리 이력 trigger `EVENT` | `processed_event`(eventId, `settlement`), 지급 전 정산서만 HOLD로 바꾸므로 다시 와도 결과가 같다 |

- Kafka 토픽 `order.events`를 `@KafkaListener`(groupId `settlement`)로 받는다(TRSS-139). 같은 토픽의 다른 주문 이벤트는 무시한다. `order.events`의 `OrderPaidEvent`도 `eventType` 헤더로 거른다(정산 코드 변경 없음).
- `order.events`에 실리는 `OrderReturnedEvent`도 `eventType` 헤더로 거르고, 헤더가 없으면 봉투를 읽어 거른다(정산 코드 변경 없음). 반품된 주문은 구매 확정되지 않아 정산 후보가 생기지 않는다.
- `SellerSanctionedEvent`는 토픽 `seller.events`를 같은 groupId `settlement`로 받는다(`SettlementSellerEventListener`). 같은 토픽의 다른 판매자 이벤트(철회 요청·철회 거절)는 정산이 할 일이 없어 무시한다. 회원은 `holdSeller`를 부르지 않는다(TRSS-161).
- 이벤트가 오기 전에 지급이 도는 틈은 지급 직전 확인으로 막는다. 정산서를 지급하기 직전에 판매자 상태가 `SANCTIONED`이면 지급하지 않고 HOLD로 바꾼다(처리 이력 trigger는 지급을 시작한 경로). 제재 뒤에 새로 만들어지는 정산서는 월 정산 집계가 판매자 상태를 보고 처음부터 HOLD로 만든다.
- 한 메시지를 한 트랜잭션에서 처리한다. eventId 처리 기록과 후보 생성이 함께 커밋되고, 후보 생성이 실패하면 기록도 롤백되어 다시 처리된다.
- 실패하면 1·2·4·8초 간격으로 다시 처리하고(총 5번), 그래도 실패하면 `order.events-dlt`로 보내고 ERROR 로그로 Slack에 알린다. 원인을 고친 뒤 `order.events`로 다시 보낸다 → [개발 가이드 6.5](palette_development_guide.md#65-outbox)
- 구매 확정 후 후보가 생기기까지 릴레이 간격(최대 약 1초)과 Kafka 전달 시간만큼 늦어진다.

## 7. delivery

배송은 `delivery-service`(포트 8085, DB 스키마 `delivery`)가 맡습니다. 주문 하나에 배송 1건이고, 플랫폼이 통합 출고합니다(물류 담당 `LOGISTICS` 역할).

### DeliveryApi

```java
package com.backend.shared.delivery.out;

public interface DeliveryApi {

    /**
     * 주문의 배송 건. 아직 없으면(결제 직후·배송지 없는 주문) empty.
     * HTTP 구현은 응답을 받지 못하면 502 EXTERNAL_SERVICE_ERROR(DomainException)를 던진다. 부르는 쪽이 잡아서 처리한다.
     */
    Optional<DeliveryDto> findByOrderId(Long orderId);
}
```

### 내부 API (HTTP)

market은 `DeliveryApi` 대신 아래 내부 API를 HTTP로 부릅니다(`market.client.DeliveryApiClient`, `custom.delivery.baseUrl`).

| DeliveryApi | 요청 | 없을 때·실패 |
| --- | --- | --- |
| `findByOrderId` | `GET /internal/v1/deliveries?orderId={orderId}` | 200 + `data: null` (배송 건 없음) |

- 응답은 다른 API와 같은 `RsData`이고 성공은 `200-1`, `data`에 `DeliveryDto`를 담습니다.
- 부르는 쪽(주문 상세)은 실패해도 주문 상세를 200으로 응답하고 `deliveryUnavailable: true`로 알립니다.
- Swagger에는 나오지 않습니다(`@Hidden`). 호출할 때는 `X-Internal-Key` 헤더가 필요합니다 → [내부 API 공통 규칙](#내부-api-공통-규칙)

### DTO

```java
package com.backend.shared.delivery.dto;

public enum DeliveryStatus { READY, SHIPPED, DELIVERED }

/** 주문 상세에 붙이는 배송 정보. READY면 carrierName·trackingNumber·shippedAt은 null */
public record DeliveryDto(Long deliveryId, DeliveryStatus status, String carrierName, String trackingNumber,
                          LocalDateTime shippedAt, LocalDateTime deliveredAt) { }
```

- `carrierName`은 택배사 표시 이름(예: "CJ대한통운")입니다.

### 받는 이벤트

| 이벤트 | 처리 | 멱등 기준 |
| --- | --- | --- |
| `OrderPaidEvent` | 주문당 배송 1건(READY)과 주문 항목별 배송 항목 생성 | `processed_event`(eventId, `delivery`)로 재전달을 거르고, `delivery.order_id` 유니크로 한 번 더 막음 |

- Kafka 토픽 `order.events`를 `@KafkaListener`(groupId `delivery`)로 받는다. 같은 토픽의 다른 주문 이벤트(`OrderConfirmedEvent` 등)는 `eventType` 헤더로 거른다.
- `OrderReturnedEvent`도 `eventType` 헤더로 거르고, 헤더가 없으면 봉투를 읽어 거른다(배송 코드 변경 없음).
- 배송지·연락처는 로그에 남기지 않는다(`deliveryId`, `orderId`, `eventId`만).

### 발행하는 이벤트

```java
package com.backend.shared.delivery.event;

/** 배송 시작(송장 등록). 알림 컨텍스트가 구매자에게 배송 시작을 알린다. aggregate: DELIVERY / deliveryId */
public record DeliveryShippedEvent(Long deliveryId, Long orderId, Long buyerId, String carrierName,
                                   String trackingNumber, LocalDateTime shippedAt) implements DomainEvent { }

/** 배송 완료. 주문 컨텍스트가 주문의 배송 완료 시각(deliveredAt)을 기록하고(구매 확정 기준), 알림 컨텍스트가 구매자에게 알린다. aggregate: DELIVERY / deliveryId */
public record DeliveryCompletedEvent(Long deliveryId, Long orderId, Long buyerId, LocalDateTime deliveredAt) implements DomainEvent { }
```

| 이벤트 | 발행 시점 | 받는 쪽 |
| --- | --- | --- |
| `DeliveryShippedEvent` | 물류 담당이 송장을 등록해 배송이 READY → SHIPPED가 될 때 (`DeliveryShipUseCase`). **송장 수정(`changeTracking`)에서는 발행하지 않는다** | 알림(groupId `notification`) |
| `DeliveryCompletedEvent` | 확인 워커가 Delivery Tracker 조회로 배송 완료를 확인해 SHIPPED → DELIVERED가 될 때 (`DeliveryCheckTrackingUseCase`). 관리자 수동 완료(`DeliveryCompleteManuallyUseCase`)도 같은 이벤트를 낸다. 둘 다 배송 행을 잠근 뒤 SHIPPED일 때만 완료하므로 한 배송에 한 번만 나간다 | market(groupId `market`), 알림(groupId `notification`) |

필드 설명

| 필드 | 이벤트 | 설명 |
| --- | --- | --- |
| `deliveryId` | 둘 다 | 배송 ID. 봉투의 `aggregateId`이자 Kafka 메시지 키 |
| `orderId` | 둘 다 | 주문 ID |
| `buyerId` | 둘 다 | 구매자(회원) ID. 알림을 받을 사람. `OrderPaidEvent.buyerId`를 배송 생성 때 저장한 값 |
| `carrierName` | Shipped | 택배사 표시 이름(예: "CJ대한통운"). `DeliveryDto.carrierName`과 같은 값 |
| `trackingNumber` | Shipped | 송장번호. 하이픈·공백을 지운 정규화 값(숫자만) |
| `shippedAt` | Shipped | 송장 등록 시각(KST, `LocalDateTime`) |
| `deliveredAt` | Completed | 배송 완료 시각(KST). 택배사 이벤트 시각, 없거나 미래면 확인 시각. 수동 완료는 관리자가 입력한 시각 |

- 배송 상태 변경과 같은 트랜잭션에서 outbox에 저장하고, 릴레이가 커밋 뒤 Kafka 토픽 `delivery.events`로 발행한다(키 `deliveryId`, 헤더 `eventType`). 송장 등록이 실패하면(READY가 아님·송장 중복·형식 오류) 이벤트도 남지 않는다.
- 같은 배송 건의 이벤트는 키가 같아 보낸 순서(시작 → 완료)대로 받는다.
- 배송지·연락처·콜백 토큰은 이벤트에 담지 않는다. 알림 문구에 필요한 값은 위 필드만으로 만든다.
- market은 groupId `market`으로 받고, `eventType` 헤더로 `DeliveryCompletedEvent`만 걸러 처리한다(`DeliveryShippedEvent`는 payload를 읽지 않고 무시, 처리 기록도 남기지 않음).
- 알림은 groupId `notification`으로 받고, `eventType` 헤더로 필요한 이벤트만 거른다.

## 8. notification

알림은 `notification-service`(포트 8086, DB 스키마 `notification`)입니다. 주문·배송 이벤트를 받아 구매자 알림을 저장하고, 구매자가 알림을 조회·읽음 처리하는 API를 제공합니다. 새 알림과 안 읽은 개수는 SSE로 실시간 전달합니다(TRSS-273). 다른 서비스를 부르지 않고, 이벤트도 발행하지 않습니다(TRSS-179).

### 알림 API (프론트용)

| 요청 | 응답 `data` | 비고 |
| --- | --- | --- |
| `GET /api/v1/notifications?type=&cursor=&size=20` | `{ items: [{ id, type, message, orderId, productName, itemCount, carrierName, trackingNumber, read, createdAt }], nextCursor, hasNext }` | 최근 30일 알림을 최신순으로. `type`을 넘기면 그 유형만(없으면 전체). 다음 페이지는 같은 `type`에 `nextCursor`를 `cursor`로 넘긴다. `size`는 1~100. 없는 `type`이면 400 `INVALID_REQUEST` |
| `GET /api/v1/notifications/unread-count` | 안 읽은 알림 개수(숫자) | 헤더 종 아이콘 뱃지용. 최근 30일 알림 중 유형과 관계없이 센다. 바뀐 개수는 SSE로도 받는다 |
| `PATCH /api/v1/notifications/{notificationId}/read` | 없음 | 이미 읽은 알림이면 그대로 성공. 없거나 다른 회원의 알림이면 404 `NOTIFICATION_NOT_FOUND` |
| `PATCH /api/v1/notifications/read-all` | 없음 | 안 읽은 내 알림을 모두 읽음 처리 |
| `GET /api/v1/notifications/subscribe` | `text/event-stream` (아래 실시간 전달) | 로그인 필요. 응답이 `RsData`가 아니라 이벤트 스트림이다. 토큰이 없거나 만료되면 401 |

- 목록은 다른 목록 API의 `PageResponse`(page·totalPages)가 아니라 커서 방식이다. 폴링 중 새 알림이 생겨도 다음 페이지가 밀리지 않게 하기 위해서다.
- `type`은 `ORDER_PAID`(주문 완료)·`DELIVERY_STARTED`(배송 시작)·`DELIVERY_COMPLETED`(배송 완료)이고, `message`는 알림을 만들 때 완성해 저장한 문구다.
- `productName`은 대표 상품명(주문의 첫 상품), `itemCount`는 주문 항목(상품 줄) 수다(수량 합계가 아님). 화면에서 "라이트 윈드브레이커 외 2개"처럼 쓴다. 주문 완료 알림에 저장하고, 배송 알림은 같은 주문의 주문 완료 알림 값을 그대로 쓴다. 주문 완료 알림이 없거나 이 필드가 생기기 전 알림이면 null이다.
- `carrierName`(택배사 표시 이름)·`trackingNumber`(송장번호)는 배송 시작 알림에만 있고 그 외는 null이다. `message`에도 같은 값이 들어 있지만, 화면은 문구를 잘라 쓰지 말고 이 필드를 쓴다.
- 최근 30일(`custom.notification.visibleDays`) 안에 생긴 알림만 목록·안 읽은 개수에 나온다. 오래된 알림은 지우지 않고 조회에서만 뺀다. 읽음 처리 API는 기간과 관계없이 동작한다.

### 실시간 전달 (SSE)

| 이벤트(`event:`) | `data` | 보내는 때 |
| --- | --- | --- |
| `unread-count` | 안 읽은 알림 개수(숫자) | 연결 직후, 새 알림 저장 뒤, 하나·모두 읽음 처리 뒤 |
| `notification` | 목록의 `items` 한 줄과 같은 JSON | 새 알림 저장 뒤. 바로 이어서 `unread-count`도 온다 |

- 다른 API처럼 `Authorization: Bearer` 헤더로 인증한다. 브라우저 기본 `EventSource`는 헤더를 붙일 수 없으므로 fetch 기반 SSE(예: `@microsoft/fetch-event-source`)로 연결한다.
- 알림 저장·읽음 처리가 커밋된 뒤에 보낸다. 롤백되면 보내지 않고, 전송이 실패해도 알림은 저장되어 있다.
- 같은 회원이 탭을 여러 개 열면 탭마다 연결되고, 모든 탭이 같은 이벤트를 받는다. 한 탭에서 읽음 처리하면 다른 탭의 뱃지도 바뀐다.
- 30초(`custom.notification.heartbeatIntervalSeconds`)마다 SSE 주석 `:heartbeat`를 보낸다. 이벤트가 아니므로 프론트는 처리하지 않아도 된다. nginx는 90초 동안 아무것도 오지 않으면 연결을 끊는다.
- 연결은 30분(`custom.notification.sseTimeoutMinutes`) 뒤 서버가 끝낸다. 끊기면 다시 연결하고, 401이면 토큰을 재발급한 뒤 다시 연결한다.
- 끊긴 동안의 알림은 다시 보내지 않는다(`Last-Event-ID` 미지원). 다시 연결하면 `unread-count`가 바로 오고, 목록은 다시 조회한다.
- 연결은 서버 메모리에 둔다. notification-service가 한 대일 때만 모든 연결로 전달된다. 여러 대로 늘리면 서버 간 전달(Redis pub/sub 등)이 필요하다.

### 받는 이벤트

| 이벤트 | 처리 | 멱등 기준 |
| --- | --- | --- |
| `OrderPaidEvent` (주문, `order.events`) | 구매자(`buyerId`)에게 주문 완료 알림 저장. `items`의 첫 상품명과 항목 수를 함께 저장 | `processed_event`(eventId, `notification`) |
| `DeliveryShippedEvent` (배송, `delivery.events`) | 구매자에게 배송 시작 알림 저장. `carrierName`·`trackingNumber`를 문구에 넣고 따로도 저장 | 위와 같음 |
| `DeliveryCompletedEvent` (배송, `delivery.events`) | 구매자에게 배송 완료 알림 저장 | 위와 같음 |

- 두 토픽을 같은 groupId `notification`으로 받는다(`NotificationOrderEventListener`, `NotificationDeliveryEventListener`). 같은 토픽의 다른 이벤트(구매 확정·반품 등)는 `eventType` 헤더로 거르고, 헤더가 없는 메시지는 봉투를 읽어 거른다.
- 알림은 다른 서비스를 다시 부르지 않고 이벤트 값만으로 알림을 만든다. 배송지·연락처는 쓰지 않는다.
- 배송지가 없는 주문은 `OrderPaidEvent`가 발행되지 않으므로 주문 완료 알림도 없다.
- 송장 수정은 이벤트가 없어 알림을 다시 보내지 않는다. 바뀐 송장번호는 주문 상세의 배송 정보로 확인한다.
- 한 메시지를 한 트랜잭션에서 처리한다. eventId 처리 기록과 알림 저장이 함께 커밋되고, 저장이 실패하면 기록도 롤백되어 다시 처리된다. 1·2·4·8초 간격으로 다시 처리하고(총 5번), 그래도 실패하면 `<토픽>-dlt`로 보낸다.
- 알림 저장이 실패해도 주문·배송 처리는 되돌리지 않는다(별도 컨슈머, groupId `notification`).

## 8.1 ai: FAQ 챗봇

FAQ 챗봇은 `ai-service`(포트 8087, DB 스키마 `ai`)의 `faq`·`chat` 컨텍스트입니다(담당 시연, TRSS-254). FAQ를 벡터(pgvector)로 의미 기반 검색하고, 찾은 FAQ만 근거로 LLM(OpenAI)이 답합니다. 다른 서비스를 부르지 않고, 이벤트도 주고받지 않습니다.

### 챗봇 API (프론트용, 로그인 필요)

| 요청 | 응답 `data` | 비고 |
| --- | --- | --- |
| `POST /api/v1/ai/chat` `{ conversationId?, message }` | `{ conversationId, answer, answerType, sources: [{ faqId, category, categoryLabel, question }] }` | `message`는 1~200자(앞뒤 공백 제거). `conversationId`가 없으면 새 대화를 시작하고, 이어서 물을 때는 응답의 `conversationId`를 그대로 보낸다. 없거나 다른 회원의 대화면 404 `CONVERSATION_NOT_FOUND`. 1분에 10번을 넘으면 429 `TOO_MANY_REQUESTS`. 질문 임베딩(OpenAI) 실패면 502 `EXTERNAL_SERVICE_ERROR` |
| `GET /api/v1/ai/chat/conversations?page=0&size=20` | `PageResponse<{ conversationId, title, lastMessageAt }>` | 내 대화를 마지막 메시지가 최근인 순으로. `title`은 첫 질문(100자까지) |
| `GET /api/v1/ai/chat/conversations/{conversationId}/messages` | `[{ messageId, role, content, answerType, sources, createdAt }]` | 대화의 질문·답변을 오래된 순으로 모두. 없거나 다른 회원의 대화면 404 `CONVERSATION_NOT_FOUND` |
| `GET /api/v1/ai/faqs/search?query=` | `{ faqs: [{ faqId, category, categoryLabel, question, answer, similarity }] }` | 질문과 뜻이 가까운 노출 중 FAQ를 유사도 높은 순으로 최대 3건. 기준값보다 낮으면 빠지고, 없으면 빈 목록. `query`는 1~200자 |

- `answerType`은 `AI`(근거 FAQ로 AI가 답함)·`FAQ`(AI가 실패·시간 초과라 가장 비슷한 FAQ 답변을 그대로 줌)·`NO_MATCH`(FAQ로 답할 수 없어 고객센터를 안내함, `sources`는 빈 목록)다. `NO_MATCH`는 관련 FAQ를 못 찾았을 때(고정 안내 문구)와, FAQ를 찾았지만 AI가 그 FAQ로 답할 수 없다고 판단했을 때(AI가 쓴 안내 문구) 둘 다다. 화면은 셋 모두 같은 말풍선으로 보여 주고, `sources`가 있으면 출처(카테고리 · 질문)를 붙인다.
- `sources`는 답변에 실제로 쓴 FAQ만 담는다. `AI`는 AI가 답변 끝에 적은 근거 번호의 FAQ(검색 순서), `FAQ`는 그대로 준 FAQ 하나다. AI의 근거 표시가 없거나 읽을 수 없으면 찾은 FAQ(최대 3건)를 모두 담는다. 대화 메시지 조회의 `sources`도 같은 기준으로 저장한 값이다.
- `role`은 `USER`(질문)·`ASSISTANT`(답변)이고, 질문 메시지의 `answerType`은 null, `sources`는 빈 목록이다. 메시지의 `sources`는 저장한 FAQ ID로 지금 FAQ의 카테고리·질문을 찾아 보여 준다(FAQ는 지우지 않으므로 숨긴 FAQ도 나온다).
- `category`는 `MEMBER`(회원)·`TOPUP`(충전)·`ORDER`(주문·결제)·`DELIVERY`(배송)·`RETURN`(반품·환불)·`SELLER`(판매·정산)이고, 화면 표시는 `categoryLabel`을 쓴다.
- FAQ를 찾기 전에 질문을 LLM으로 FAQ 질문 같은 문장으로 다시 쓴다(구어·줄임말을 쇼핑몰 용어로, 이어지는 질문은 이전 대화로 생략된 대상을 채움). 다시 쓰기가 실패하면 같은 대화의 최근 질문 1개 + 이번 질문으로 찾는다. 답변에는 최근 메시지 6개를 프롬프트에 넣는다. 주문 조회·취소 같은 처리는 하지 않는다(FAQ 안내만).
- 대화 목록·메시지 조회와 FAQ 검색은 요청 수 제한에 세지 않는다.

### 관리자 FAQ API (ADMIN)

| 요청 | 응답 `data` | 비고 |
| --- | --- | --- |
| `GET /api/v1/admin/faqs?category=` | `[{ faqId, category, categoryLabel, question, answer, visible, embedded, createdAt, updatedAt }]` | 숨긴 FAQ까지 최근 등록 순으로 모두(페이지 없음). `category`를 넘기면 그 카테고리만 |
| `POST /api/v1/admin/faqs` `{ category, question, answer, visible }` | `{ faqId }` | `question` 200자, `answer` 2000자까지(앞뒤 공백 제거). 없는 `category`면 400 `INVALID_REQUEST` |
| `PUT /api/v1/admin/faqs/{faqId}` `{ category, question, answer }` | 위 목록의 한 건 | 세 값을 모두 보낸다. 질문·답변이 바뀌면 다시 임베딩될 때까지 `embedded=false`. 없으면 404 `FAQ_NOT_FOUND` |
| `PATCH /api/v1/admin/faqs/{faqId}/visibility` `{ visible }` | 위 목록의 한 건 | `visible=false`면 챗봇 검색에서 빠진다. 임베딩은 그대로라 다시 켜면 바로 검색된다. 없으면 404 `FAQ_NOT_FOUND` |

- 삭제 API는 없다. 내리려면 노출을 끈다(대화 기록의 근거 FAQ가 남고, 실수해도 되돌릴 수 있다).
- `embedded`는 챗봇 검색 반영 여부다. 등록·수정 뒤 스케줄러가 5초마다 임베딩해 보통 수 초 안에 `true`가 되고, OpenAI 장애 중이면 복구될 때까지 `false`다. 화면은 `false`를 "반영 중"으로 보여 준다.
- nginx는 `/api/v1/ai/**`와 `/api/v1/admin/faqs/**`를 ai-service로 보낸다.

## 9. 구현 주의사항

계약의 모양과는 별개로, 구현할 때 꼭 지켜야 하는 규칙입니다.

### 9.1 결제는 주문을 PAYING으로 커밋한 뒤 호출하고, 같은 키일 때만 결과를 반영한다 (주문·결제)

결제는 HTTP라 주문 트랜잭션에 참여하지 못합니다. 그래서 "결제 요청 → 결과 반영"을 트랜잭션 두 개로 나누고, 결과를 모르면 바로 취소하지 않고 결제 중(`PAYING`)으로 남깁니다.

1. 상품 판매 가능 여부, 주문 만료 시각, 예약 쿠폰을 지금 쓸 수 있는지 확인한다 (`409-PRODUCT_NOT_ON_SALE`, `409-ORDER_EXPIRED`, `409-COUPON_NOT_ACTIVE`)
2. 주문을 잠그고 `PAYING`으로 바꾼 뒤, 이번 시도의 멱등키(`payment_idempotency_key`)와 시작 시각(`paying_started_at`)을 저장하고 커밋한다. 이미 `PAYING`이면 `409-PAYMENT_IN_PROGRESS`
3. `PaymentApi.pay(command)`를 트랜잭션 밖에서 부른다
4. 주문을 다시 잠그고, **아직 `PAYING`이고 같은 키일 때만** 결과를 반영한다
   - PAID: 예약 쿠폰 사용 처리, 주문 `PAID`, 장바구니 항목 삭제, `OrderPaidEvent`(outbox)를 한 트랜잭션으로. 만료 시각이 지났어도 PAID (payment가 만료 전 결제로 판단)
   - `FAILED_*`: `PAYMENT_FAILED` (다시 결제하거나, 만료되면 만료 스케줄러가 취소·재고 복구). 결제 취소는 부르지 않는다
   - payment가 요청을 거절(4xx): 결제가 없으므로 `PAYMENT_FAILED`
   - 응답 시간 초과·응답 없음·5xx·반영 트랜잭션 실패: `PAYING`으로 두고 `200-2`(결제 확인 중)로 응답
5. 재확인 스케줄러: `PAYING`이 된 지 30초(`custom.order.payingRecheckSeconds`)가 지난 주문에 **같은 키·같은 내용으로 `pay`를 다시 불러** 4번과 같이 반영한다
   - 읽기 전용 조회는 쓰지 않는다. "결제 없음"은 확정이 아니다(원래 요청이 늦게 도착할 수 있음)
   - 결과가 PAID인데 주문이 이 키로 결제 중도 결제 완료도 아니면(예외 상태) `cancelPayment(키)`로 보상하고 ERROR 로그(주문 ID·키만)
   - 계속 응답이 없으면 `PAYING` 유지, 10분(`payingAlertMinutes`)이 넘으면 ERROR 로그

`PAYING` 주문은 재결제·취소·만료 처리·판매 중지 취소 대상이 아닙니다. 회원 탈퇴·판매자 진행 중 판매 검사와 장바구니·주문 생성 중복 검사에는 들어갑니다.
결제 쪽은 `pay`의 키 UNIQUE와 지갑 잠금으로 같은 키의 요청을 직렬화하고, 실패 기록(`FAILED_*`)은 별도 트랜잭션(REQUIRES_NEW)으로 남깁니다.

### 9.2 재고 차감·복원 모두 상품 ID 오름차순으로 잠근다 (상품)

- 주문 하나에 여러 상품이 들어 있으므로, 판매 중지 → 결제 대기 주문 취소 → 재고 복원 과정에서 다른 상품 행도 잠급니다.
- `deductStock`과 `restoreStock`의 잠금 순서가 다르면 동시에 들어온 주문 생성과 데드락이 날 수 있습니다. 두 메서드 모두 상품 ID 오름차순으로 잠급니다.
- 판매 중지 → 주문 취소 → 재고 복원은 한 트랜잭션 안에서 같은 상품을 다시 수정합니다. 한 트랜잭션 안에서 **벌크 UPDATE 쿼리와 엔티티 수정을 섞지 않습니다**(영속성 컨텍스트 값이 DB와 달라짐).

### 9.3 구매 확정은 배송 완료 뒤에만, 경로 3개 모두 `OrderConfirmedEvent`를 발행한다 (주문)

구매 확정은 배송 완료 뒤에만 할 수 있습니다(배송지 없는 기존 주문은 결제 기준). 배송지 있는 주문이 배송 완료 전이면 409 `ORDER_NOT_DELIVERED`.

| 경로 | `ConfirmType` |
| --- | --- |
| 구매자 수동 확정 (배송 완료 뒤) | `MANUAL` |
| 배송 완료 후 7일 자동 확정 (배송지 없는 주문은 결제 후 7일, RETURN_REQUESTED 제외) | `AUTO` |
| 반품 거절 시 배송 완료 후 7일이 이미 지났으면(배송지 없는 주문은 결제 후 7일) 즉시 확정 | `RETURN_REJECTED` |

한 경로라도 이벤트를 빠뜨리면 정산 후보가 만들어지지 않아 판매자에게 정산되지 않습니다. 구매 확정(`order.confirm(type)`)과 `OrderConfirmedEvent` 발행(`EventPublisher.publish`)은 주문 컨텍스트의 메서드 하나로 묶고, 세 경로 모두 그 메서드를 호출합니다.

- 7일 자동 확정은 벌크 UPDATE를 쓰지 않고, 대상 주문을 조회해 한 건씩 위 메서드로 확정합니다(이벤트·상태 이력 누락 방지).
- 세 경로의 UseCase 테스트에서 이벤트 발행을 검증합니다.

## 10. 진행 순서

1. 각 담당자가 이 문서에서 자기 컨텍스트 부분을 검토하고, 고칠 점을 PR 코멘트나 팀 채널에 남깁니다.
2. 확정되면 `shared/<ctx>/dto·event·out`에 코드로 옮기고, 자기 컨텍스트에 `<Ctx>ApiAdapter` 임시 구현을 함께 올립니다.
3. 다섯 명의 계약 PR을 한 번에 `develop`에 머지한 뒤 각자 구현을 시작합니다.

## 11. 정할 것

- [x] 주문 생성 시 재고 이력의 `orderItemId` 처리 (유진·은정)
- [ ] 정산 지급 시 판매자 1명의 여러 주문을 `SettledOrderAmountDto` 목록으로 넘기는 방식이 홀딩 처리에 맞는지 (다은·지은)
- [x] 결제 `PROCESSING`은 쓰지 않아 제거 (지은)
- [x] 주문 항목에 정가 단가·할인율을 저장하고 장바구니·주문 상세에 정가·할인율 표시, 이후 `OptionSnapshotDto` 호환 생성자 제거 (유진·은정)
- [x] 판매자의 회원 탈퇴: 판매자는 판매자 철회 완료(WITHDRAWN) 후에만 회원 탈퇴할 수 있다. 철회 승인 시점에 진행 중인 판매(`OrderApi.hasActiveSales`)와 미정산 금액(`SettlementApi.getUnsettledAmount`)이 없어야 한다. 탈퇴 후 정산금이 탈퇴 회원 지갑으로 들어가는 것을 막기 위함 (시연·지은·은정·다은)
