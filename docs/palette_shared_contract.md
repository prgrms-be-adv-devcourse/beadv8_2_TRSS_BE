# shared 계약 초안

컨텍스트끼리 주고받는 **동기 Api 인터페이스, DTO, 이벤트**의 초안입니다. 규칙은 [개발 가이드 6장](palette_development_guide.md#6-컨텍스트-간-통신)을 따릅니다.

- 이 문서는 **초안**입니다. 각 담당자가 자기 컨텍스트 부분을 검토해 `shared/<ctx>` 코드로 옮기고, 바꾼 내용은 이 문서에도 반영합니다.
- 메서드와 필드는 [기획서 3.7 모듈 간 내부 API](palette_proposal.md)와 [정책](palette_policy.md)을 기준으로 뽑았습니다.


| 컨텍스트 | 담당 | 제공하는 Api | 발행하는 이벤트 |
| --- | --- | --- | --- |
| member | 시연·지은 | `MemberApi` | `MemberSignedUpEvent`, `SellerSanctionedEvent`, `SellerWithdrawalRequestedEvent`, `SellerWithdrawalRejectedEvent` |
| product | 유진 | `ProductApi` | `WishAddedEvent`, `WishRemovedEvent` |
| review | 유진 | - | `ReviewCreatedEvent`, `ReviewModifiedEvent`, `ReviewDeletedEvent` |
| order | 은정 | `OrderApi` | `OrderPaidEvent`, `OrderConfirmedEvent`, `OrderReturnedEvent` |
| payment | 지은·시연 | `PaymentApi` | - |
| settlement | 다은 | `SettlementApi` | - |
| delivery | 은정 | `DeliveryApi` | `DeliveryShippedEvent`, `DeliveryCompletedEvent` |

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
- 외부에는 열지 않는다. nginx는 `/api/` 등만 앱으로 넘기고 `/internal/`은 넘기지 않는다. 게이트웨이를 만들 때도 `/internal/**`은 라우팅하지 않는다.
- 내부 API 컨트롤러는 Swagger에 나오지 않게 `@Hidden`을 붙이고, 응답은 다른 API와 같은 `RsData`로 돌려준다.

## 1. 시나리오별 호출 흐름

계약이 왜 필요한지 보여 주는 표입니다. "같은 Tx"는 호출한 쪽 트랜잭션에 참여한다는 뜻입니다.

| 시나리오 | 시작 | 호출 순서 | 방식 |
| --- | --- | --- | --- |
| 회원 가입 | member | 회원 저장 → `MemberSignedUpEvent` → (커밋 뒤 Kafka `member.events`) 결제가 지갑 생성 | 이벤트, 별도 Tx (지갑 생성이 실패해도 가입은 성공) |
| 장바구니 조회 | order | `ProductApi.getOptions(optionIds)` → 현재 가격·판매 가능 여부·대표 이미지로 예상 금액 계산 | 동기 조회 |
| 장바구니 담기·주문 생성 시 본인 상품 검사 | order | `MemberApi.findSellerByMemberId(buyerId)` → 상품의 `sellerId`와 비교 | 동기 조회 |
| 주문 생성 | order | 장바구니 잠금 → (같은 `Idempotency-Key`의 주문이 있으면 그 주문을 반환하고 끝, 취소된 주문이면 409) → `ProductApi.getOptions` → `MemberApi.getSellers`(판매자명 스냅샷) → 주문·주문 항목 저장 → `ProductApi.deductStock` | 동기, 같은 Tx |
| 결제 | order | 주문 PAID로 조건부 UPDATE(먼저) → `PaymentApi.pay(command)` → 실패면 함께 롤백 | 동기, 같은 Tx |
| 결제 후 배송 생성 | order | 결제 성공 + 배송지가 있는 주문이면 `OrderPaidEvent` → (커밋 뒤 Kafka `order.events`) delivery가 배송(READY) 생성 | 이벤트, 별도 Tx (결제가 실패하면 이벤트도 함께 롤백) |
| 배송 시작 | delivery | 물류 담당 송장 등록 → 배송 SHIPPED → `DeliveryShippedEvent` → (Kafka `delivery.events`) 알림이 구매자에게 배송 시작 알림 | 이벤트, 별도 Tx (송장 수정은 이벤트 없음) |
| 배송 완료 | delivery | Delivery Tracker로 배송 완료 확인 → 배송 DELIVERED → `DeliveryCompletedEvent` → (Kafka `delivery.events`) market이 주문 `deliveredAt` 기록, 알림이 구매자에게 배송 완료 알림 | 이벤트, 별도 Tx |
| 주문 상세의 배송 정보 | order | `DeliveryApi.findByOrderId(orderId)` (배송지 있고 PAID 이후 주문만) | 동기 조회 (조회 실패 시 주문 상세는 200 + `deliveryUnavailable: true`) |
| 주문 취소·15분 만료 | order | `ProductApi.lockProductsForOrderChange(productIds)` → 주문 잠금·상태 다시 확인 → 주문 CANCELED → `ProductApi.restoreStock(ORDER_RESTORE)` | 동기, 같은 Tx |
| 반품 승인 | order | 주문 RETURNED → 복원 선택 항목만 `ProductApi.restoreStock(RETURN_RESTORE)` → `OrderReturnedEvent` | 동기 + 이벤트 |
| 반품 환불 | payment | `OrderReturnedEvent` 수신 → 홀딩 → 구매자 지갑(REFUND) (미구현) | 이벤트 |
| 구매 확정 (수동·7일 자동·반품 거절 시 즉시, 배송지 있는 주문은 배송 완료 뒤) | order | 주문 CONFIRMED → `OrderConfirmedEvent` | 이벤트 |
| 정산 후보 생성 | settlement | `OrderConfirmedEvent` 수신(Kafka `order.events`) → 주문 항목별 후보(READY) | 이벤트 |
| 월 정산 지급 | settlement | `MemberApi.getSeller(sellerId)` → `PaymentApi.transfer(command)` | 동기 |
| 상품 삭제·관리자 즉시 중지·판매 중지 요청 승인 | product | 상품 잠금 → 상품 DELETED/STOPPED → 심사 중인 중지 요청 CANCELED → `OrderApi.cancelPendingOrders(productIds, PRODUCT_STOPPED)` → (주문이) `ProductApi.restoreStock` | 동기, 같은 Tx |
| 판매자 철회 요청 | member | `SettlementApi.getUnsettledAmount(sellerId)` 0인지 확인 → 판매자 WITHDRAW_REQUESTED → `SellerWithdrawalRequestedEvent` → (market) 판매 중 상품 STOPPED, 결제 대기 주문 취소·재고 복원 | 이벤트 (미정산 확인은 동기 조회, 조회 실패 시 503) |
| 판매자 철회 승인 | member | `OrderApi.hasActiveSales(sellerId)` · `SettlementApi.getUnsettledAmount(sellerId)` 확인 → 판매자 WITHDRAWN (상품은 철회 요청 때 이미 중지) | 동기 조회 (조회 실패 시 503) |
| 판매자 철회 거절 | member | 판매자 APPROVED로 되돌림 → `SellerWithdrawalRejectedEvent` → (market) 철회로 중지된 상품만 판매 중으로 | 이벤트 |
| 관리자 제재 | member | 판매자 SANCTIONED → `SellerSanctionedEvent` → (market) 판매 중 상품 STOPPED · (settlement) 지급 전 정산서 HOLD | 이벤트 |
| 회원 탈퇴 | member | 판매자 상태가 PENDING·APPROVED·WITHDRAW_REQUESTED면 거절(SELLER_ACTIVE) → `OrderApi.hasActiveOrders` · `PaymentApi.getWallet`(잔액 0) · `PaymentApi.hasPendingTopups`(READY·PROCESSING 충전 건) · 판매자 정보가 있으면 상태와 무관하게 `OrderApi.hasActiveSales` · `SettlementApi.getUnsettledAmount` | 동기 조회 (조회 실패 시 503, 지갑이 없으면 잔액 0) |
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
| `MemberPaymentPort` | `MemberPaymentHttpAdapter` | 결제 `/internal/v1/payments` (payment-service로 분리되기 전에는 루트 앱) | `custom.payment.baseUrl` (`PAYMENT_SERVICE_URL`, 기본 `http://localhost:8080`) |

- HTTP 호출은 `MemberInternalApiClient`가 맡습니다. 모든 요청에 `X-Internal-Key`를 붙이고, 접속 1초·응답 3초 제한을 둡니다.
- 실패는 두 가지로 나눕니다.
  - 상대가 4xx `RsData` 오류로 응답: 같은 `resultCode`의 `DomainException` (예: `404-WALLET_NOT_FOUND`)
  - 응답을 받지 못함(접속 실패·시간 초과), 상대 5xx, 읽을 수 없는 응답: `MemberPortUnavailableException` (확인하지 못함)
- 확인하지 못했을 때의 처리는 값의 쓰임에 따라 다릅니다.

| 쓰임 | 해당 기능 | 확인하지 못하면 |
| --- | --- | --- |
| 판단 (통과 여부 결정) | 회원 탈퇴 조건 조회·요청, 판매자 철회 요청·승인 | 통과시키지 않고 `503-WITHDRAWAL_CHECK_UNAVAILABLE` ("탈퇴 가능 여부를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.") |
| 표시 (보여 주기만 함) | 내 판매자 정보 조회·수정의 `unsettledAmount`, 관리자 회원 상세의 `wallet`·`recentOrders` | 그 값만 `null`로 비우고 나머지는 그대로 응답 (빈 목록과 `null`은 다르다: `null`은 조회하지 못함) |

- 탈퇴 검사에서 지갑이 없으면(`404-WALLET_NOT_FOUND`, 가입 직후 지갑 생성 전) 잔액 0으로 봅니다.
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
     */
    List<OptionSnapshotDto> getOptions(Collection<Long> optionIds);

    /**
     * 상품 여러 개의 현재 정보 (상품 단위). 쿠폰 발행과 협약 pick 상품 목록에서 쓴다.
     * 삭제, 판매 중지 상품도 포함하고(onSale = false) 없는 ID만 빠진다. 순서는 보장하지 않는다.
     */
    List<ProductSnapshotDto> getProducts(Collection<Long> productIds);

    /**
     * 주문 생성 시 재고 차감(ORDER_DEDUCT). 주문 항목을 저장해 orderItemId가 생긴 뒤 같은 트랜잭션에서 호출한다.
     * 상품(읽기 잠금) → 옵션(쓰기 잠금) 순서로, 각각 ID 오름차순으로 잠근다.
     * 실패: 409 PRODUCT_NOT_ON_SALE(판매 불가), 400 OUT_OF_STOCK(재고 부족), 404 PRODUCT_NOT_FOUND (하나라도 실패하면 전체 롤백)
     * 재고만 바꾸고 상품 상태는 바꾸지 않는다(품절은 재고로 계산).
     * 이미 차감한 주문 항목으로 다시 호출하면 아무것도 하지 않고 정상 종료한다.
     */
    void deductStock(List<StockChangeDto> items);

    /**
     * 재고 복원. 주문 취소·만료·판매 중지로 인한 취소는 ORDER_RESTORE, 반품 승인은 RETURN_RESTORE.
     * 재고만 바꾸고 상품 상태는 바꾸지 않는다(STOPPED 상품도 재고만 늘어난다).
     * 삭제된 상품·옵션은 ORDER_RESTORE만 재고를 늘리고, RETURN_RESTORE면 409 PRODUCT_DELETED / OPTION_DELETED
     * (주문 컨텍스트가 반품 승인에서 삭제된 대상을 미리 거른다).
     * 복원 결과가 옵션 최대 재고(1,000,000)를 넘으면 400 STOCK_LIMIT_EXCEEDED.
     * 같은 주문 항목·사유로 다시 호출하면 아무것도 하지 않고 정상 종료한다. 차감한 적 없는 항목이면 IllegalStateException.
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

### DTO

```java
package com.backend.shared.product.dto;

/**
 * 옵션 하나의 현재 정보. saleable = 상품 ON_SALE + 옵션 삭제 안 됨 + 카테고리 사용 중 (재고와는 무관).
 * 재고 수량·본인 상품·결제 대기 주문 여부는 주문 컨텍스트가 따로 판단한다.
 * unsaleableReason은 saleable이면 null, 여러 개에 해당하면 enum 선언 순서가 우선순위다.
 * optionName은 옵션이 하나뿐인 상품이면 "기본". thumbnailUrl은 대표 이미지가 없으면 null.
 */
public record OptionSnapshotDto(Long optionId, Long productId, Long sellerId, String productName, String optionName,
                                long price, int stock, boolean saleable,
                                ProductUnsaleableReason unsaleableReason, String thumbnailUrl) { }

/** 주문 항목 한 줄. orderId·orderItemId는 재고 이력에 남고 중복 처리를 막는 기준이 된다. quantity는 1 이상 */
public record StockChangeDto(Long orderId, Long orderItemId, Long optionId, int quantity) { }

/** 품절은 판매 불가 이유가 아니다 (재고는 stock으로 판단) */
public enum ProductUnsaleableReason { PRODUCT_DELETED, OPTION_DELETED, PRODUCT_STOPPED, CATEGORY_INACTIVE }
public enum StockRestoreReason { ORDER_RESTORE, RETURN_RESTORE }
```

- 확정(10/10): 주문과 주문 항목을 먼저 저장해 `orderItemId`를 만든 뒤, 같은 트랜잭션에서 `deductStock`을 호출합니다.
- 잠금 순서는 항상 **상품 → 주문(→ 옵션)**입니다. 상품 삭제·중지는 상품을 잠근 뒤 `cancelPendingOrders`로 주문을 잠그므로, 주문을 먼저 잠그는 흐름은 `lockProductsForOrderChange`로 상품을 먼저 잠급니다.
- 구현 상태: 모든 메서드가 `ProductApiAdapter`에 구현되어 있습니다.

### 받는 이벤트

| 이벤트 | 처리 | 멱등 기준 |
| --- | --- | --- |
| `SellerSanctionedEvent` | `stopAllBySeller(sellerId, reason)`와 같은 처리 | `processed_event`(eventId, `market`), 판매 중 상품만 바꾸므로 다시 와도 결과가 같다 |
| `SellerWithdrawalRequestedEvent` | `stopAllBySeller(sellerId, reason)`와 같은 처리 | 위와 같음 |
| `SellerWithdrawalRejectedEvent` | `resumeAllBySeller(sellerId)`와 같은 처리 | `processed_event`(eventId, `market`), 철회로 중지된 상품만 바꾸므로 다시 와도 결과가 같다 |

- Kafka 토픽 `seller.events`를 market-service의 `ProductSellerEventListener`(`@KafkaListener` groupId `market`)로 받는다. 한 메시지를 한 트랜잭션에서 처리하고, 실패하면 재시도 후 `seller.events-dlt`로 보낸다.
- 회원은 `stopAllBySeller`·`resumeAllBySeller`를 부르지 않는다(TRSS-160). 두 메서드는 market 안에서 컨슈머가 쓴다.

### 발행하는 이벤트

```java
package com.backend.shared.product.event;

/** 구매자가 상품을 찜함. 이미 찜한 상품을 다시 찜하면 발행하지 않는다 */
public record WishAddedEvent(Long memberId, Long productId) implements DomainEvent { }   // aggregateType PRODUCT, aggregateId productId

/** 구매자가 찜을 해제함. 찜하지 않은 상품을 해제하면 발행하지 않는다 */
public record WishRemovedEvent(Long memberId, Long productId) implements DomainEvent { }  // aggregateType PRODUCT, aggregateId productId
```

- 찜 추가·해제와 같은 트랜잭션에서 outbox에 저장하고, 릴레이가 Kafka 토픽 `product.events`로 발행한다(키 `productId`, 같은 상품의 찜 이벤트는 순서대로 전달된다).
- 아직 받는 쪽은 없다. 상품 검색 색인(ES)의 찜 수 반영에 쓸 예정이다(TRSS-167).

## 3.1 review

리뷰는 market-service 안의 별도 컨텍스트(`boundedContext/review`)다. 다른 컨텍스트에 Api를 제공하지 않고, 아래 공유 Api만 부른다.

| 부르는 Api | 용도 |
| --- | --- |
| `OrderApi.findConfirmedOrderItem(buyerId, orderItemId)` | 리뷰 작성 자격 확인(본인 주문 + 구매 확정). 상품·옵션 ID와 주문 시점 옵션명도 여기서 가져온다 (TRSS-177) |
| `MemberApi.getMember(memberId)` | 작성자 이름(가운데 글자를 가려 리뷰에 저장)과 탈퇴 여부 |
| `ProductApi.getOptions(optionIds)` | 내 리뷰 목록의 상품명·대표 이미지 |

### 발행하는 이벤트

```java
package com.backend.shared.review.event;

/** 구매자가 리뷰를 작성함 */
public record ReviewCreatedEvent(Long reviewId, Long productId, int rating) implements DomainEvent { }                 // aggregateType REVIEW, aggregateId productId

/** 구매자가 리뷰를 수정함. 별점이 그대로여도(내용만 바뀌어도) 발행한다 */
public record ReviewModifiedEvent(Long reviewId, Long productId, int oldRating, int rating) implements DomainEvent { }  // aggregateType REVIEW, aggregateId productId

/** 구매자가 리뷰를 삭제함(소프트 삭제). rating은 삭제 직전 별점 */
public record ReviewDeletedEvent(Long reviewId, Long productId, int rating) implements DomainEvent { }                 // aggregateType REVIEW, aggregateId productId
```

- 리뷰 작성·수정·삭제와 같은 트랜잭션에서 outbox에 저장하고, 릴레이가 Kafka 토픽 `review.events`로 발행한다(키 `productId`, 같은 상품의 리뷰 이벤트는 순서대로 전달된다).
- 별점을 함께 실어서, 받는 쪽은 리뷰를 다시 조회하지 않고 상품별 평점 합계·리뷰 수를 증감할 수 있다.
- 아직 받는 쪽은 없다. 상품 검색 색인(ES)의 평점·리뷰 수 반영에 쓸 예정이다(TRSS-186).

## 4. order

### OrderApi

```java
package com.backend.shared.order.out;

public interface OrderApi {

    /**
     * 상품이 들어 있는 결제 대기(CREATED·PAYMENT_FAILED) 주문을 모두 취소하고 재고를 복원한다.
     * 상품 삭제·판매 중지(관리자 즉시 중지·중지 요청 승인)·판매자 철회·제재 때 상품 컨텍스트가 상품을 잠근 뒤 호출한다. 호출한 쪽 트랜잭션에 참여한다.
     * 취소한 주문 수를 반환한다.
     */
    int cancelPendingOrders(Collection<Long> productIds, OrderCancelType cancelType);

    /** 구매자로서 진행 중인 주문(CREATED·PAYMENT_FAILED·PAID·RETURN_REQUESTED)이 있는지. 회원 탈퇴 검사용 */
    boolean hasActiveOrders(Long buyerId);

    /**
     * 판매자 상품이 들어간 진행 중인 주문(CREATED·PAYMENT_FAILED·PAID·RETURN_REQUESTED)이 있는지.
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
}
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
}
```

- 쿠폰 컨텍스트가 부르는 다른 기능은 모두 쿠폰 쪽 포트를 거친다: CouponSellerPort(MemberApi), CouponProductPort(ProductApi), CouponOrderPort(OrderApi).
- 인플루언서(CouponInfluencerPort)와 pick(InfluencerPickPort)은 아직 구현이 없어 임시 구현체가 "인플루언서 아님", "pick 안 함"으로 답한다. 인플루언서와 pick이 생기면 구현체만 바꾼다.
- influencerId는 인플루언서 테이블 ID(influencer.id)이고 회원 ID가 아니다.
- 판매자 제재(SellerSanctionedEvent)는 쿠폰 컨슈머가 groupId market-coupon으로 따로 받아 협약을 종료한다.

### 이벤트

```java
package com.backend.shared.order.event;

/** 결제 완료. 배송지가 있는 주문만 발행한다. 배송 컨텍스트가 배송(READY)을 만든다. aggregate: ORDER / orderId */
public record OrderPaidEvent(Long orderId, String orderNumber, Long buyerId, LocalDateTime paidAt,
                             ShippingAddressDto shippingAddress, List<PaidOrderItemDto> items) implements DomainEvent { }

/** 구매 확정. 정산 컨텍스트가 주문 항목별 정산 후보를 만든다. aggregate: ORDER / orderId */
public record OrderConfirmedEvent(Long orderId, Long buyerId, LocalDateTime confirmedAt,
                                  ConfirmType confirmType, List<ConfirmedOrderItemDto> items) implements DomainEvent { }

/** 반품 승인. 결제 컨텍스트가 홀딩 금액을 구매자 지갑으로 환불한다. aggregate: ORDER / orderId */
public record OrderReturnedEvent(Long orderId, Long buyerId, long refundAmount, LocalDateTime returnedAt) implements DomainEvent { }
```

### DTO

```java
package com.backend.shared.order.dto;

public record ConfirmedOrderItemDto(Long orderItemId, Long productId, Long sellerId,
                                    long unitPrice, int quantity, long lineAmount) { }

/** 주문 배송지 스냅샷 (주문할 때 받은 값 그대로). 로그에 남기지 않는다 */
public record ShippingAddressDto(String receiverName, String receiverPhone, String zipCode,
                                 String address, String addressDetail, String requestMessage) { }

/** 결제된 주문 항목 (배송 피킹 목록용). 주문 항목에 저장된 스냅샷 값 */
public record PaidOrderItemDto(Long orderItemId, Long productId, Long optionId, Long sellerId, String sellerName,
                               String productName, String optionName, int quantity) { }

public enum OrderCancelType { USER, EXPIRED, PRODUCT_STOPPED, SELLER_SANCTIONED }
public enum ConfirmType { MANUAL, AUTO, RETURN_REJECTED }
```

## 5. payment

### PaymentApi

```java
package com.backend.shared.payment.out;

public interface PaymentApi {

    /** 잔액과 홀딩 중 금액. 지갑이 없으면 404 WALLET_NOT_FOUND */
    WalletSummaryDto getWallet(Long memberId);

    /** 결과가 정해지지 않은 충전 건(READY·PROCESSING)이 있는지. 회원 탈퇴 검사용 (반영 전이라 잔액에 잡히지 않음) */
    boolean hasPendingTopups(Long memberId);

    /**
     * 주문 결제: 구매자 지갑을 잠그고 지갑 → 홀딩, 원장 PAYMENT 기록. 호출한 쪽 트랜잭션에 참여한다.
     * 잔액 부족·주문 만료는 예외가 아니라 결과로 반환한다. 실패 기록은 별도 트랜잭션(REQUIRES_NEW)으로 남긴다.
     * 주문 쪽은 결과가 PAID가 아니면 DomainException(402 INSUFFICIENT_BALANCE 등)을 던진다.
     * 같은 idempotencyKey로 다시 호출하면 처음 결과를 그대로 반환한다.
     */
    PayResultDto pay(PayCommand command);

    /**
     * 주문의 결제 시도 목록 (주문 상세의 결제 내역). 오래된 순, 실패 시도 포함.
     * 내 주문인지는 주문 쪽에서 확인하고 호출한다. 결제 시도가 없으면 빈 목록을 반환한다.
     */
    List<PaymentAttemptDto> getPaymentAttempts(Long orderId);

    /**
     * 정산 지급: 홀딩 → 판매자 지갑(SETTLEMENT), 홀딩 → 관리자 지갑(FEE).
     * 주문별 홀딩의 settled_amount를 늘리고, 다 차면 SETTLED로 바꾼다.
     * idempotencyKey(SETTLEMENT-{settlementId})가 이미 처리됐으면 기존 결과를 반환한다.
     * 호출한 쪽 트랜잭션에 참여(REQUIRED)한다.
     * 오류: 404 WALLET_NOT_FOUND, 404 HOLDING_NOT_FOUND, 409 HOLDING_INSUFFICIENT, 409 TRANSFER_AMOUNT_MISMATCH
     */
    TransferResultDto transfer(SettlementTransferCommand command);

    /** 정산 지급 여부 조회 (재시도·대사용). 없으면 empty */
    Optional<TransferResultDto> findTransfer(String idempotencyKey);
}
```

### DTO

```java
package com.backend.shared.payment.dto;

public record WalletSummaryDto(Long memberId, long balance, long heldAmount) { }

public record PayCommand(Long orderId, String orderNumber, Long buyerId, long amount,
                         LocalDateTime orderExpiresAt, String idempotencyKey) { }

/** shortage: 잔액 부족일 때 모자란 금액, chargeAmount: 1만 원 단위로 올린 충전 권장 금액 */
public record PayResultDto(Long paymentId, PayResultStatus status, long shortage, long chargeAmount) {
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
| `OrderReturnedEvent` (미구현) | 환불(REQUESTED → COMPLETED), 홀딩 RELEASED, 결제 REFUNDED, 원장 REFUND | `payment_refund.payment_id` 유니크(주문당 PAID 결제는 1건) |
| `MemberSignedUpEvent` | 잔액 0인 지갑 생성 (Kafka `member.events`, `@KafkaListener` groupId `payment`, 가입과 별도 트랜잭션) | `processed_event`(eventId, `payment`)로 재전달을 거르고, 지갑이 이미 있으면 무시(`wallet.member_id` 유니크) |

- 시스템 오류(`FAILED_SYSTEM_ERROR`)는 결과가 아니라 예외로 올라옵니다. 주문 쪽은 별도 트랜잭션으로 주문을 PAYMENT_FAILED로 바꾼 뒤 예외를 다시 던집니다. (미구현: 지금은 예외가 그대로 올라가 전체가 롤백되고, FAILED_SYSTEM_ERROR·PAYMENT_FAILED는 기록되지 않는다)

## 6. settlement

정산은 `settlement-service`(포트 8084, DB 스키마 `settlement`)로 옮겼습니다. 다른 서비스와의 연결은 서비스 간 연결 작업 전까지 임시 구현입니다.

| 방향 | 임시 구현 | 동작 |
| --- | --- | --- |
| 정산 → 회원 (`getSeller`, `getSellers`, `findSellerByMemberId`) | `settlement-service`의 `MemberApiClient` | 지어낸 판매자를 돌려주지 않음: `getSeller`·`getSellers` 예외, `findSellerByMemberId` empty. 연결 전까지 월 정산 집계(제재 여부 확인), 관리자 정산 목록·미리보기(상호명 조회), 정산 보류 해제(제재 여부 확인)는 실패하고, 판매자 정산 조회는 빈 결과(목록·예상 금액은 빈 목록, 상세는 404), 시드 정산 데이터는 만들어지지 않는다 |
| 정산 → 결제 (`transfer`) | `settlement-service`의 `PaymentApiClient` | 예외. 그래서 정산 스케줄러는 기본으로 꺼 둔다(`SETTLEMENT_SCHEDULING_ENABLED=false`) |
| 주문 → 정산 (`OrderConfirmedEvent`) | 없음 (Kafka `order.events`) | 그대로 동작. 앱이 달라도 같은 토픽을 받는다 |

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
| `DeliveryCompletedEvent` | 확인 워커가 Delivery Tracker 조회로 배송 완료를 확인해 SHIPPED → DELIVERED가 될 때 (`DeliveryCheckTrackingUseCase`) | market(groupId `market`), 알림(groupId `notification`) |

필드 설명

| 필드 | 이벤트 | 설명 |
| --- | --- | --- |
| `deliveryId` | 둘 다 | 배송 ID. 봉투의 `aggregateId`이자 Kafka 메시지 키 |
| `orderId` | 둘 다 | 주문 ID |
| `buyerId` | 둘 다 | 구매자(회원) ID. 알림을 받을 사람. `OrderPaidEvent.buyerId`를 배송 생성 때 저장한 값 |
| `carrierName` | Shipped | 택배사 표시 이름(예: "CJ대한통운"). `DeliveryDto.carrierName`과 같은 값 |
| `trackingNumber` | Shipped | 송장번호. 하이픈·공백을 지운 정규화 값(숫자만) |
| `shippedAt` | Shipped | 송장 등록 시각(KST, `LocalDateTime`) |
| `deliveredAt` | Completed | 배송 완료 시각(KST). 택배사 이벤트 시각, 없거나 미래면 확인 시각 |

- 배송 상태 변경과 같은 트랜잭션에서 outbox에 저장하고, 릴레이가 커밋 뒤 Kafka 토픽 `delivery.events`로 발행한다(키 `deliveryId`, 헤더 `eventType`). 송장 등록이 실패하면(READY가 아님·송장 중복·형식 오류) 이벤트도 남지 않는다.
- 같은 배송 건의 이벤트는 키가 같아 보낸 순서(시작 → 완료)대로 받는다.
- 배송지·연락처·콜백 토큰은 이벤트에 담지 않는다. 알림 문구에 필요한 값은 위 필드만으로 만든다.
- market은 groupId `market`으로 받고, `eventType` 헤더로 `DeliveryCompletedEvent`만 걸러 처리한다(`DeliveryShippedEvent`는 payload를 읽지 않고 무시, 처리 기록도 남기지 않음).
- 알림은 groupId `notification`으로 받고, `eventType` 헤더로 필요한 이벤트만 거른다.

## 8. 구현 주의사항

계약의 모양과는 별개로, 구현할 때 꼭 지켜야 하는 규칙입니다.

### 8.1 결제는 주문 상태를 먼저 바꾼 뒤 호출한다 (주문·결제)

결제와 15분 만료가 동시에 일어나도 둘 중 하나만 반영되게 하려면 주문 행을 먼저 잠가야 합니다.

1. 주문을 PAID로 조건부 UPDATE: `where id = ? and status in ('CREATED','PAYMENT_FAILED') and expires_at > now()`
2. 바뀐 행이 0건이면 409 `ORDER_EXPIRED`(또는 상태 오류)를 던진다
3. `PaymentApi.pay()`를 호출한다. 결과가 PAID가 아니면 402 `INSUFFICIENT_BALANCE` 등을 던져서 1번까지 함께 롤백한다
4. 결제 실패 기록(`FAILED_*`)은 결제 컨텍스트가 별도 트랜잭션(REQUIRES_NEW)으로 남기므로 롤백되지 않는다

주문 행을 먼저 잠그면 만료 스케줄러는 결제가 끝날 때까지 기다립니다. 순서를 거꾸로 하면 지갑은 잠갔는데 주문이 만료되는 경우를 따로 처리해야 합니다.

### 8.2 재고 차감·복원 모두 상품 ID 오름차순으로 잠근다 (상품)

- 주문 하나에 여러 상품이 들어 있으므로, 판매 중지 → 결제 대기 주문 취소 → 재고 복원 과정에서 다른 상품 행도 잠급니다.
- `deductStock`과 `restoreStock`의 잠금 순서가 다르면 동시에 들어온 주문 생성과 데드락이 날 수 있습니다. 두 메서드 모두 상품 ID 오름차순으로 잠급니다.
- 판매 중지 → 주문 취소 → 재고 복원은 한 트랜잭션 안에서 같은 상품을 다시 수정합니다. 한 트랜잭션 안에서 **벌크 UPDATE 쿼리와 엔티티 수정을 섞지 않습니다**(영속성 컨텍스트 값이 DB와 달라짐).

### 8.3 구매 확정은 배송 완료 뒤에만, 경로 3개 모두 `OrderConfirmedEvent`를 발행한다 (주문)

구매 확정은 배송 완료 뒤에만 할 수 있습니다(배송지 없는 기존 주문은 결제 기준). 배송지 있는 주문이 배송 완료 전이면 409 `ORDER_NOT_DELIVERED`.

| 경로 | `ConfirmType` |
| --- | --- |
| 구매자 수동 확정 (배송 완료 뒤) | `MANUAL` |
| 배송 완료 후 7일 자동 확정 (배송지 없는 주문은 결제 후 7일, RETURN_REQUESTED 제외) | `AUTO` |
| 반품 거절 시 배송 완료 후 7일이 이미 지났으면(배송지 없는 주문은 결제 후 7일) 즉시 확정 | `RETURN_REJECTED` |

한 경로라도 이벤트를 빠뜨리면 정산 후보가 만들어지지 않아 판매자에게 정산되지 않습니다. 구매 확정(`order.confirm(type)`)과 `OrderConfirmedEvent` 발행(`EventPublisher.publish`)은 주문 컨텍스트의 메서드 하나로 묶고, 세 경로 모두 그 메서드를 호출합니다.

- 7일 자동 확정은 벌크 UPDATE를 쓰지 않고, 대상 주문을 조회해 한 건씩 위 메서드로 확정합니다(이벤트·상태 이력 누락 방지).
- 세 경로의 UseCase 테스트에서 이벤트 발행을 검증합니다.

## 9. 진행 순서

1. 각 담당자가 이 문서에서 자기 컨텍스트 부분을 검토하고, 고칠 점을 PR 코멘트나 팀 채널에 남깁니다.
2. 확정되면 `shared/<ctx>/dto·event·out`에 코드로 옮기고, 자기 컨텍스트에 `<Ctx>ApiAdapter` 임시 구현을 함께 올립니다.
3. 다섯 명의 계약 PR을 한 번에 `develop`에 머지한 뒤 각자 구현을 시작합니다.

## 10. 정할 것

- [x] 주문 생성 시 재고 이력의 `orderItemId` 처리 (유진·은정)
- [ ] 정산 지급 시 판매자 1명의 여러 주문을 `SettledOrderAmountDto` 목록으로 넘기는 방식이 홀딩 처리에 맞는지 (다은·지은)
- [x] 결제 `PROCESSING`은 쓰지 않아 제거 (지은)
- [x] 판매자의 회원 탈퇴: 판매자는 판매자 철회 완료(WITHDRAWN) 후에만 회원 탈퇴할 수 있다. 철회 승인 시점에 진행 중인 판매(`OrderApi.hasActiveSales`)와 미정산 금액(`SettlementApi.getUnsettledAmount`)이 없어야 한다. 탈퇴 후 정산금이 탈퇴 회원 지갑으로 들어가는 것을 막기 위함 (시연·지은·은정·다은)
