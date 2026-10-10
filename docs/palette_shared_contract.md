# shared 계약 초안

컨텍스트끼리 주고받는 **동기 Api 인터페이스, DTO, 이벤트**의 초안입니다. 규칙은 [개발 가이드 6장](palette_development_guide.md#6-컨텍스트-간-통신)을 따릅니다.

- 이 문서는 **초안**입니다. 각 담당자가 자기 컨텍스트 부분을 검토해 `shared/<ctx>` 코드로 옮기고, 바꾼 내용은 이 문서에도 반영합니다.
- 메서드와 필드는 [기획서 3.7 모듈 간 내부 API](palette_proposal.md)와 [정책](palette_policy.md)을 기준으로 뽑았습니다.


| 컨텍스트 | 담당 | 제공하는 Api | 발행하는 이벤트 |
| --- | --- | --- | --- |
| member | 시연·지은 | `MemberApi` | - |
| product | 유진 | `ProductApi` | - |
| order | 은정 | `OrderApi` | `OrderConfirmedEvent`, `OrderReturnedEvent` |
| payment | 지은·시연 | `PaymentApi` | - |
| settlement | 다은 | `SettlementApi` | - |

## 1. 시나리오별 호출 흐름

계약이 왜 필요한지 보여 주는 표입니다. "같은 Tx"는 호출한 쪽 트랜잭션에 참여한다는 뜻입니다.

| 시나리오 | 시작 | 호출 순서 | 방식 |
| --- | --- | --- | --- |
| 회원 가입 | member | `PaymentApi.createWallet(memberId)` | 동기, 같은 Tx (정책: 회원과 지갑을 한 트랜잭션으로) |
| 장바구니 조회 | order | `ProductApi.getProducts(ids)` → 현재 가격·상태로 예상 금액 계산 | 동기 조회 |
| 장바구니 담기·주문 생성 시 본인 상품 검사 | order | `MemberApi.findSellerByMemberId(buyerId)` → 상품의 `sellerId`와 비교 | 동기 조회 |
| 주문 생성 | order | `ProductApi.getProducts` → `MemberApi.getSellers`(판매자명 스냅샷) → `ProductApi.deductStock` | 동기, 같은 Tx |
| 결제 | order | 주문 PAID로 조건부 UPDATE(먼저) → `PaymentApi.pay(command)` → 실패면 함께 롤백 | 동기, 같은 Tx |
| 주문 취소·15분 만료 | order | 주문 CANCELED → `ProductApi.restoreStock(ORDER_RESTORE)` | 동기, 같은 Tx |
| 반품 승인 | order | 주문 RETURNED → 복원 선택 항목만 `ProductApi.restoreStock(RETURN_RESTORE)` → `OrderReturnedEvent` | 동기 + Outbox |
| 반품 환불 | payment | `OrderReturnedEvent` 수신 → 홀딩 → 구매자 지갑(REFUND) | 이벤트 |
| 구매 확정 (수동·7일 자동·반품 거절 시 즉시) | order | 주문 CONFIRMED → `OrderConfirmedEvent` | Outbox |
| 정산 후보 생성 | settlement | `OrderConfirmedEvent` 수신 → 주문 항목별 후보(READY) | 이벤트 |
| 월 정산 지급 | settlement | `MemberApi.getSeller(sellerId)` → `PaymentApi.transfer(command)` | 동기 |
| 판매 중지 승인·상품 삭제 | product | 상품 STOPPED/DELETED → `OrderApi.cancelPendingOrders(productIds, PRODUCT_STOPPED)` → (주문이) `ProductApi.restoreStock` | 동기, 같은 Tx |
| 판매자 철회 요청 | member | `SettlementApi.getUnsettledAmount(sellerId)` 0인지 확인 | 동기 조회 |
| 판매자 철회 승인 | member | `OrderApi.hasActiveSales(sellerId)` · `SettlementApi.getUnsettledAmount(sellerId)` 확인 → 판매자 WITHDRAWN → `ProductApi.stopAllBySeller(sellerId, ...)` | 동기, 같은 Tx |
| 관리자 제재 | member | 판매자 WITHDRAWN → `ProductApi.stopAllBySeller` → `SettlementApi.holdSeller(sellerId, reason)` | 동기, 같은 Tx |
| 회원 탈퇴 | member | 판매자 신청 이력이 있으면 WITHDRAWN(철회 완료)인지 확인 → `OrderApi.hasActiveOrders` · `PaymentApi.getWallet`(잔액 0) · 판매자였으면 `SettlementApi.getUnsettledAmount` | 동기 조회 |

## 2. member

### MemberApi

```java
package com.backend.shared.member.out;

public interface MemberApi {

    /** 회원 조회. 없으면 404 MEMBER_NOT_FOUND */
    MemberDto getMember(Long memberId);

    /** 회원의 판매자 정보. 판매자 신청 이력이 없으면 empty (상태와 관계없이 최신 1건) */
    Optional<SellerDto> findSellerByMemberId(Long memberId);

    /** 판매자 조회. 없으면 404 SELLER_NOT_FOUND */
    SellerDto getSeller(Long sellerId);

    /** 여러 판매자 조회 (주문 스냅샷·정산 목록용). 없는 ID는 결과에서 빠짐 */
    List<SellerDto> getSellers(Collection<Long> sellerIds);
}
```

### DTO

```java
package com.backend.shared.member.dto;

public record MemberDto(Long id, String email, String name, MemberRole role, MemberStatus status) { }

/** 계좌 정보는 넣지 않는다 (정산은 예치금 지갑으로 지급) */
public record SellerDto(Long sellerId, Long memberId, String businessName,
                        SellerCategory category, SellerStatus status) {
    public boolean canSell() { return status == SellerStatus.APPROVED; }
}

public enum MemberRole { USER, SELLER, ADMIN }
public enum MemberStatus { ACTIVE, INACTIVE, DELETED }
public enum SellerStatus { PENDING, APPROVED, REJECTED, WITHDRAW_REQUESTED, WITHDRAWN }
public enum SellerCategory { BEAUTY, CLOTHING }
```

## 3. product

### ProductApi

```java
package com.backend.shared.product.out;

public interface ProductApi {

    /**
     * 상품 여러 개의 현재 정보. 장바구니 담기·조회와 주문 생성에 쓴다.
     * 삭제된 상품도 결과에 포함하고(status = DELETED, saleable = false), 없는 ID만 빠진다.
     * 판매 가능 여부는 saleable로 판단한다. 결과 순서는 보장하지 않는다.
     */
    List<ProductSnapshotDto> getProducts(Collection<Long> productIds);

    /**
     * 주문 생성 시 재고 차감(ORDER_DEDUCT). 호출한 쪽 트랜잭션에 참여한다.
     * 주문 항목을 저장해 orderItemId가 생긴 뒤 호출한다.
     * 데드락을 막기 위해 상품 ID 오름차순으로 잠근다.
     * 실패: 400 OUT_OF_STOCK, 409 PRODUCT_NOT_ON_SALE(SOLD_OUT·STOPPED·DELETED·카테고리 사용 중지) (하나라도 실패하면 전체 롤백)
     * 재고가 0이 되면 SOLD_OUT으로 바꾼다.
     * 이미 차감한 주문 항목으로 다시 호출하면 아무것도 하지 않고 정상 종료한다.
     */
    void deductStock(List<StockChangeDto> items);

    /**
     * 재고 복원. 주문 취소·만료·판매 중지 취소는 ORDER_RESTORE, 반품 승인은 RETURN_RESTORE.
     * SOLD_OUT은 ON_SALE로 바꾸고, STOPPED는 재고만 늘린다.
     * DELETED는 ORDER_RESTORE만 재고를 늘리고, RETURN_RESTORE면 409 PRODUCT_DELETED
     * (주문 컨텍스트가 반품 승인에서 삭제된 상품을 미리 거른다).
     * 같은 주문 항목·사유로 다시 호출하면 아무것도 하지 않고 정상 종료한다.
     */
    void restoreStock(List<StockChangeDto> items, StockRestoreReason reason);

    /**
     * 판매자의 판매 중인 상품을 모두 STOPPED로 바꾼다(판매자 철회 승인·관리자 제재).
     * 내부에서 OrderApi.cancelPendingOrders도 호출한다. 중지한 상품 ID를 반환한다.
     */
    List<Long> stopAllBySeller(Long sellerId, String reason);
}
```

### DTO

```java
package com.backend.shared.product.dto;

/**
 * saleable = ON_SALE이고 카테고리가 사용 중. 상품 자체 조건만 본다.
 * 재고 수량·본인 상품·결제 대기 주문 여부는 주문 컨텍스트가 orderable로 최종 판단한다.
 * unsaleableReason은 saleable이면 null.
 */
public record ProductSnapshotDto(Long productId, Long sellerId, String name, long price,
                                 int stock, ProductStatus status, boolean saleable,
                                 ProductUnsaleableReason unsaleableReason, String thumbnailUrl) { }

/** 주문 항목 한 줄. orderId·orderItemId는 재고 이력에 남고 중복 처리를 막는 기준이 된다. quantity는 1 이상 */
public record StockChangeDto(Long orderId, Long orderItemId, Long productId, int quantity) { }

public enum ProductStatus { ON_SALE, SOLD_OUT, STOPPED, DELETED }
public enum ProductUnsaleableReason { SOLD_OUT, STOPPED, DELETED, CATEGORY_INACTIVE }
public enum StockRestoreReason { ORDER_RESTORE, RETURN_RESTORE }
```

- 확정(10/10): 주문과 주문 항목을 먼저 저장해 `orderItemId`를 만든 뒤, 같은 트랜잭션에서 `deductStock`을 호출합니다.
- 구현 상태: `getProducts`·`deductStock`·`restoreStock`은 `ProductApiAdapter`에 임시 구현이 있습니다(임의 ID에 판매 중인 임시 상품 반환, 재고 변경 없음).

## 4. order

### OrderApi

```java
package com.backend.shared.order.out;

public interface OrderApi {

    /**
     * 상품이 들어 있는 결제 대기(CREATED·PAYMENT_FAILED) 주문을 모두 취소하고 재고를 복원한다.
     * 판매 중지 승인·상품 삭제·판매자 철회·제재 때 상품 컨텍스트가 호출한다. 호출한 쪽 트랜잭션에 참여한다.
     * 취소한 주문 수를 반환한다.
     */
    int cancelPendingOrders(Collection<Long> productIds, OrderCancelType cancelType);

    /** 구매자로서 진행 중인 주문(CREATED·PAYMENT_FAILED·PAID·RETURN_REQUESTED)이 있는지. 회원 탈퇴 검사용 */
    boolean hasActiveOrders(Long buyerId);

    /**
     * 판매자 상품이 들어간 진행 중인 주문(CREATED·PAYMENT_FAILED·PAID·RETURN_REQUESTED)이 있는지.
     * 판매자 철회 승인 검사용. 있으면 회원 컨텍스트가 409로 승인을 거절한다.
     */
    boolean hasActiveSales(Long sellerId);
}
```

### 이벤트 (Outbox 대상)

```java
package com.backend.shared.order.event;

/** 구매 확정. 정산 컨텍스트가 주문 항목별 정산 후보를 만든다 */
public record OrderConfirmedEvent(Long orderId, Long buyerId, LocalDateTime confirmedAt,
                                  ConfirmType confirmType, List<ConfirmedOrderItemDto> items) { }

/** 반품 승인. 결제 컨텍스트가 홀딩 금액을 구매자 지갑으로 환불한다 */
public record OrderReturnedEvent(Long orderId, Long buyerId, long refundAmount, LocalDateTime returnedAt) { }
```

### DTO

```java
package com.backend.shared.order.dto;

public record ConfirmedOrderItemDto(Long orderItemId, Long productId, Long sellerId,
                                    long unitPrice, int quantity, long lineAmount) { }

public enum OrderCancelType { USER, EXPIRED, PRODUCT_STOPPED, SELLER_SANCTIONED }
public enum ConfirmType { MANUAL, AUTO, RETURN_REJECTED }
```

## 5. payment

### PaymentApi

```java
package com.backend.shared.payment.out;

public interface PaymentApi {

    /** 지갑 생성(잔액 0). 회원 가입 트랜잭션에 참여한다. 이미 있으면 아무것도 하지 않는다 */
    void createWallet(Long memberId);

    /** 잔액과 홀딩 중 금액. 지갑이 없으면 404 WALLET_NOT_FOUND */
    WalletSummaryDto getWallet(Long memberId);

    /**
     * 주문 결제: 구매자 지갑을 잠그고 지갑 → 홀딩, 원장 PAYMENT 기록. 호출한 쪽 트랜잭션에 참여한다.
     * 잔액 부족·주문 만료는 예외가 아니라 결과로 반환한다. 실패 기록은 별도 트랜잭션(REQUIRES_NEW)으로 남긴다.
     * 주문 쪽은 결과가 PAID가 아니면 DomainException(402 INSUFFICIENT_BALANCE 등)을 던진다.
     * 같은 idempotencyKey로 다시 호출하면 처음 결과를 그대로 반환한다.
     */
    PayResultDto pay(PayCommand command);

    /**
     * 정산 지급: 홀딩 → 판매자 지갑(SETTLEMENT), 홀딩 → 관리자 지갑(FEE).
     * 주문별 홀딩의 settled_amount를 늘리고, 다 차면 SETTLED로 바꾼다.
     * idempotencyKey(SETTLEMENT-{settlementId})가 이미 처리됐으면 기존 결과를 반환한다.
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

public record PayCommand(Long orderId, Long buyerId, long amount,
                         LocalDateTime orderExpiresAt, String idempotencyKey) { }

/** shortage: 잔액 부족일 때 모자란 금액, chargeAmount: 1만 원 단위로 올린 충전 권장 금액 */
public record PayResultDto(Long paymentId, PayResultStatus status, long shortage, long chargeAmount) {
    public boolean isPaid() { return status == PayResultStatus.PAID; }
}

public enum PayResultStatus { PAID, FAILED_INSUFFICIENT_BALANCE, FAILED_ORDER_EXPIRED }

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
| `OrderReturnedEvent` | 환불(REQUESTED → COMPLETED), 홀딩 RELEASED, 결제 REFUNDED, 원장 REFUND | `payment_refund.payment_id` 유니크(주문당 PAID 결제는 1건) |

- 시스템 오류(`FAILED_SYSTEM_ERROR`)는 결과가 아니라 예외로 올라옵니다. 주문 쪽은 별도 트랜잭션으로 주문을 PAYMENT_FAILED로 바꾼 뒤 예외를 다시 던집니다.

## 6. settlement

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

### 받는 이벤트

| 이벤트 | 처리 | 멱등 기준 |
| --- | --- | --- |
| `OrderConfirmedEvent` | 주문 항목마다 정산 후보 생성(수수료 = 판매가 × 10 ÷ 100 버림, 정산월 = 확정일 KST 기준 월) | `settlement_candidate.order_item_id` 유니크 |

## 7. 구현 주의사항

계약의 모양과는 별개로, 구현할 때 꼭 지켜야 하는 규칙입니다.

### 7.1 결제는 주문 상태를 먼저 바꾼 뒤 호출한다 (주문·결제)

결제와 15분 만료가 동시에 일어나도 둘 중 하나만 반영되게 하려면 주문 행을 먼저 잠가야 합니다.

1. 주문을 PAID로 조건부 UPDATE: `where id = ? and status in ('CREATED','PAYMENT_FAILED') and expires_at > now()`
2. 바뀐 행이 0건이면 409 `ORDER_EXPIRED`(또는 상태 오류)를 던진다
3. `PaymentApi.pay()`를 호출한다. 결과가 PAID가 아니면 402 `INSUFFICIENT_BALANCE` 등을 던져서 1번까지 함께 롤백한다
4. 결제 실패 기록(`FAILED_*`)은 결제 컨텍스트가 별도 트랜잭션(REQUIRES_NEW)으로 남기므로 롤백되지 않는다

주문 행을 먼저 잠그면 만료 스케줄러는 결제가 끝날 때까지 기다립니다. 순서를 거꾸로 하면 지갑은 잠갔는데 주문이 만료되는 경우를 따로 처리해야 합니다.

### 7.2 재고 차감·복원 모두 상품 ID 오름차순으로 잠근다 (상품)

- 주문 하나에 여러 상품이 들어 있으므로, 판매 중지 → 결제 대기 주문 취소 → 재고 복원 과정에서 다른 상품 행도 잠급니다.
- `deductStock`과 `restoreStock`의 잠금 순서가 다르면 동시에 들어온 주문 생성과 데드락이 날 수 있습니다. 두 메서드 모두 상품 ID 오름차순으로 잠급니다.
- 판매 중지 → 주문 취소 → 재고 복원은 한 트랜잭션 안에서 같은 상품을 다시 수정합니다. 한 트랜잭션 안에서 **벌크 UPDATE 쿼리와 엔티티 수정을 섞지 않습니다**(영속성 컨텍스트 값이 DB와 달라짐).

### 7.3 구매 확정 경로 3개 모두 `OrderConfirmedEvent`를 발행한다 (주문)

| 경로 | `ConfirmType` |
| --- | --- |
| 구매자 수동 확정 | `MANUAL` |
| 결제 후 7일 자동 확정 (RETURN_REQUESTED 제외) | `AUTO` |
| 반품 거절 시 결제 후 7일이 이미 지났으면 즉시 확정 | `RETURN_REJECTED` |

한 경로라도 이벤트를 빠뜨리면 정산 후보가 만들어지지 않아 판매자에게 정산되지 않습니다. 확정 처리는 엔티티의 메서드 하나(`order.confirm(type)`)로 모으고, 그 안에서 이벤트를 발행합니다.

## 8. 진행 순서

1. 각 담당자가 이 문서에서 자기 컨텍스트 부분을 검토하고, 고칠 점을 PR 코멘트나 팀 채널에 남깁니다.
2. 확정되면 `shared/<ctx>/dto·event·out`에 코드로 옮기고, 자기 컨텍스트에 `<Ctx>ApiAdapter` 임시 구현을 함께 올립니다.
3. 다섯 명의 계약 PR을 한 번에 `develop`에 머지한 뒤 각자 구현을 시작합니다.

## 9. 정할 것

- [x] 주문 생성 시 재고 이력의 `orderItemId` 처리 (유진·은정)
- [ ] 정산 지급 시 판매자 1명의 여러 주문을 `SettledOrderAmountDto` 목록으로 넘기는 방식이 홀딩 처리에 맞는지 (다은·지은)
- [x] 결제 `PROCESSING`은 일단 두고, 쓰지 않으면 2주차에 제거 (지은)
- [x] 판매자의 회원 탈퇴: 판매자는 판매자 철회 완료(WITHDRAWN) 후에만 회원 탈퇴할 수 있다. 철회 승인 시점에 진행 중인 판매(`OrderApi.hasActiveSales`)와 미정산 금액(`SettlementApi.getUnsettledAmount`)이 없어야 한다. 탈퇴 후 정산금이 탈퇴 회원 지갑으로 들어가는 것을 막기 위함 (시연·지은·은정·다은)
