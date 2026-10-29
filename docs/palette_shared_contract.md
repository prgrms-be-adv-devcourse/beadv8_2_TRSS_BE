# shared 계약 초안

컨텍스트끼리 주고받는 **동기 Api 인터페이스, DTO, 이벤트**의 초안입니다. 규칙은 [개발 가이드 6장](palette_development_guide.md#6-컨텍스트-간-통신)을 따릅니다.

- 이 문서는 **초안**입니다. 각 담당자가 자기 컨텍스트 부분을 검토해 `shared/<ctx>` 코드로 옮기고, 바꾼 내용은 이 문서에도 반영합니다.
- 메서드와 필드는 [기획서 3.7 모듈 간 내부 API](palette_proposal.md)와 [정책](palette_policy.md)을 기준으로 뽑았습니다.


| 컨텍스트 | 담당 | 제공하는 Api | 발행하는 이벤트 |
| --- | --- | --- | --- |
| member | 시연·지은 | `MemberApi` | `MemberSignedUpEvent` |
| product | 유진 | `ProductApi` | - |
| order | 은정 | `OrderApi` | `OrderConfirmedEvent`, `OrderReturnedEvent` |
| payment | 지은·시연 | `PaymentApi` | - |
| settlement | 다은 | `SettlementApi` | - |

## 1. 시나리오별 호출 흐름

계약이 왜 필요한지 보여 주는 표입니다. "같은 Tx"는 호출한 쪽 트랜잭션에 참여한다는 뜻입니다.

| 시나리오 | 시작 | 호출 순서 | 방식 |
| --- | --- | --- | --- |
| 회원 가입 | member | 회원 저장 → `MemberSignedUpEvent` → 결제가 지갑 생성 | 이벤트, 같은 Tx (정책: 회원과 지갑을 한 트랜잭션으로) |
| 장바구니 조회 | order | `ProductApi.getOptions(optionIds)` → 현재 가격·판매 가능 여부·대표 이미지로 예상 금액 계산 | 동기 조회 |
| 장바구니 담기·주문 생성 시 본인 상품 검사 | order | `MemberApi.findSellerByMemberId(buyerId)` → 상품의 `sellerId`와 비교 | 동기 조회 |
| 주문 생성 | order | 장바구니 잠금 → (같은 `Idempotency-Key`의 주문이 있으면 그 주문을 반환하고 끝, 취소된 주문이면 409) → `ProductApi.getOptions` → `MemberApi.getSellers`(판매자명 스냅샷) → 주문·주문 항목 저장 → `ProductApi.deductStock` | 동기, 같은 Tx |
| 결제 | order | 주문 PAID로 조건부 UPDATE(먼저) → `PaymentApi.pay(command)` → 실패면 함께 롤백 | 동기, 같은 Tx |
| 주문 취소·15분 만료 | order | `ProductApi.lockProductsForOrderChange(productIds)` → 주문 잠금·상태 다시 확인 → 주문 CANCELED → `ProductApi.restoreStock(ORDER_RESTORE)` | 동기, 같은 Tx |
| 반품 승인 | order | 주문 RETURNED → 복원 선택 항목만 `ProductApi.restoreStock(RETURN_RESTORE)` → `OrderReturnedEvent` | 동기 + 이벤트 |
| 반품 환불 | payment | `OrderReturnedEvent` 수신 → 홀딩 → 구매자 지갑(REFUND) (미구현) | 이벤트 |
| 구매 확정 (수동·7일 자동·반품 거절 시 즉시) | order | 주문 CONFIRMED → `OrderConfirmedEvent` | 이벤트 |
| 정산 후보 생성 | settlement | `OrderConfirmedEvent` 수신 → 주문 항목별 후보(READY) | 이벤트 |
| 월 정산 지급 | settlement | `MemberApi.getSeller(sellerId)` → `PaymentApi.transfer(command)` | 동기 |
| 상품 삭제·관리자 즉시 중지·판매 중지 요청 승인 | product | 상품 잠금 → 상품 DELETED/STOPPED → 심사 중인 중지 요청 CANCELED → `OrderApi.cancelPendingOrders(productIds, PRODUCT_STOPPED)` → (주문이) `ProductApi.restoreStock` | 동기, 같은 Tx |
| 판매자 철회 요청 | member | `SettlementApi.getUnsettledAmount(sellerId)` 0인지 확인 → 판매자 WITHDRAW_REQUESTED → `ProductApi.stopAllBySeller(sellerId, reason)` (판매 중 상품 STOPPED, 결제 대기 주문 취소·재고 복원) | 동기, 같은 Tx |
| 판매자 철회 승인 | member | `OrderApi.hasActiveSales(sellerId)` · `SettlementApi.getUnsettledAmount(sellerId)` 확인 → 판매자 WITHDRAWN (상품은 철회 요청 때 이미 중지) | 동기, 같은 Tx |
| 판매자 철회 거절 | member | 판매자 APPROVED로 되돌림 → `ProductApi.resumeAllBySeller(sellerId)` (철회로 중지된 상품만 판매 중으로) | 동기, 같은 Tx |
| 관리자 제재 | member | 판매자 SANCTIONED → `ProductApi.stopAllBySeller(sellerId, reason)` → `SettlementApi.holdSeller(sellerId, reason)` | 동기, 같은 Tx |
| 회원 탈퇴 | member | 판매자 상태가 PENDING·APPROVED·WITHDRAW_REQUESTED면 거절(SELLER_ACTIVE) → `OrderApi.hasActiveOrders` · `PaymentApi.getWallet`(잔액 0) · `PaymentApi.hasPendingTopups`(READY·PROCESSING 충전 건) · 판매자 정보가 있으면 상태와 무관하게 `OrderApi.hasActiveSales` · `SettlementApi.getUnsettledAmount` | 동기 조회 |
| 충전 준비·승인, 주문 생성·결제 | payment, order | `MemberApi.checkActiveMember(memberId)`: 탈퇴한 회원이면 401 (탈퇴 후 남은 Access Token 차단) | 동기 조회 |
| 관리자 회원 상세 | member | `PaymentApi.getWallet` · `OrderApi.getRecentOrders(memberId, 5)` | 동기 조회 |
| 관리자 결제·충전 조회 | payment | `MemberApi.findMemberByEmail`(이메일 검색) · `MemberApi.getMembers`(이메일 표시) | 동기 조회 |

## 2. member

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

    /** 이메일로 회원 조회 (정확히 일치, 관리자 결제·충전 검색). 없으면 empty */
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

/** 회원 가입 완료. 결제가 받아 잔액 0인 지갑을 생성한다 */
public record MemberSignedUpEvent(Long memberId) { }
```

- 지금은 `@EventListener`로 가입 트랜잭션 안에서 처리해, 지갑 생성이 실패하면 가입도 롤백된다.

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
}
```

### 이벤트

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
| `MemberSignedUpEvent` | 잔액 0인 지갑 생성 (`@EventListener`, 가입 트랜잭션 안에서 처리) | `wallet.member_id` 유니크, 이미 있으면 무시 |

- 시스템 오류(`FAILED_SYSTEM_ERROR`)는 결과가 아니라 예외로 올라옵니다. 주문 쪽은 별도 트랜잭션으로 주문을 PAYMENT_FAILED로 바꾼 뒤 예외를 다시 던집니다. (미구현: 지금은 예외가 그대로 올라가 전체가 롤백되고, FAILED_SYSTEM_ERROR·PAYMENT_FAILED는 기록되지 않는다)

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

한 경로라도 이벤트를 빠뜨리면 정산 후보가 만들어지지 않아 판매자에게 정산되지 않습니다. 구매 확정(`order.confirm(type)`)과 `OrderConfirmedEvent` 발행(`EventPublisher.publish`)은 주문 컨텍스트의 메서드 하나로 묶고, 세 경로 모두 그 메서드를 호출합니다.

- 7일 자동 확정은 벌크 UPDATE를 쓰지 않고, 대상 주문을 조회해 한 건씩 위 메서드로 확정합니다(이벤트·상태 이력 누락 방지).
- 세 경로의 UseCase 테스트에서 이벤트 발행을 검증합니다.

## 8. 진행 순서

1. 각 담당자가 이 문서에서 자기 컨텍스트 부분을 검토하고, 고칠 점을 PR 코멘트나 팀 채널에 남깁니다.
2. 확정되면 `shared/<ctx>/dto·event·out`에 코드로 옮기고, 자기 컨텍스트에 `<Ctx>ApiAdapter` 임시 구현을 함께 올립니다.
3. 다섯 명의 계약 PR을 한 번에 `develop`에 머지한 뒤 각자 구현을 시작합니다.

## 9. 정할 것

- [x] 주문 생성 시 재고 이력의 `orderItemId` 처리 (유진·은정)
- [ ] 정산 지급 시 판매자 1명의 여러 주문을 `SettledOrderAmountDto` 목록으로 넘기는 방식이 홀딩 처리에 맞는지 (다은·지은)
- [x] 결제 `PROCESSING`은 쓰지 않아 제거 (지은)
- [x] 판매자의 회원 탈퇴: 판매자는 판매자 철회 완료(WITHDRAWN) 후에만 회원 탈퇴할 수 있다. 철회 승인 시점에 진행 중인 판매(`OrderApi.hasActiveSales`)와 미정산 금액(`SettlementApi.getUnsettledAmount`)이 없어야 한다. 탈퇴 후 정산금이 탈퇴 회원 지갑으로 들어가는 것을 막기 위함 (시연·지은·은정·다은)
