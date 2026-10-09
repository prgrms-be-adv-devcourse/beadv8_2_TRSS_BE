# 프로젝트 기획서

## 1. 프로젝트 소개

### 1.1 프로젝트 이름

**팔레트**

### 1.2 판매하는 상품(서비스)

다채로운 컬러를 한 곳에 담은 팔레트처럼, 다양한 셀러의 뷰티·의류 상품으로 나만의 취향과 스타일을 완성하는 입점형 이커머스 플랫폼이다.

플랫폼은 중개·결제·정산을 담당하며, 토스페이먼츠 연동 예치금 시스템과 월 정산(수수료 10%) 구조를 통해 구매자와 판매자 모두에게 안정적인 거래 환경을 제공한다.

| 대분류 | 소분류 |
| --- | --- |
| 뷰티 | 메이크업, 바디케어, 선케어, 마스크팩, 클렌징, 스킨케어, 헤어케어, 건강식품 |
| 의류 | 상의, 아우터, 바지, 원피스/스커트, 신발, 가방, 모자, 소품 |

- 핵심 서비스 정책
    - 상품은 옵션 없는 단일 품목이고, 금액은 원 단위 정수다.
    - 모든 주문은 **예치금**으로 결제하고, 예치금은 토스페이먼츠로 충전한다(10,000원 단위, 1회 1만~100만 원).
    - 한 주문에 여러 판매자의 상품을 담을 수 있고, 주문 상태는 주문서 하나에 하나만 둔다.
    - 결제 금액은 구매 확정 전까지 플랫폼이 보관(홀딩)하고, 확정된 금액을 다음 달 10일 판매자 예치금 지갑에 정산한다.
    - 픽업·배송은 이번 범위에서 제외한다(추후 추가 예정).

### 1.3 주요 기능

| 기능 | 사용자 | 핵심 내용 | 세미 프로젝트 개발 범위 |
| --- | --- | --- | --- |
| 회원/인증 | 비회원, 회원 | 이메일 인증 가입, 지갑 자동 생성, JWT 로그인, 회원 탈퇴 | 포함 |
| 판매자 | 회원, 관리자 | 판매자 입점 신청, 관리자 승인(SELLER 권한), 철회 요청·관리자 제재, 계좌/상품 관리 | 포함 |
| 관리자 | 관리자 | 판매자 심사·제재, 상품 판매 중지·중지 요청 심사, 결제·실패 거래 조회, 반품 승인, 수동 정산 | 포함 |
| 상품·카테고리·재고 | 판매자, 관리자 | 상품 등록·수정·삭제, 판매 중지(해제 없음), 2단계 카테고리, 재고 조정 및 변경 이력 관리 | 포함 |
| 검색 | 모두 | 상품명·카테고리 검색, 최신/가격순 정렬, 품절 상품 제외 | 포함 |
| 장바구니 | 회원 | 여러 판매자 상품 담기, 수량·선택 변경, 조회 시 가격·재고 재확인, 예상 결제 금액 | 포함 |
| 주문·취소·반품·구매확정 | 회원, 관리자 | 주문 생성 시 재고 차감(생성 후 15분 미결제 시 만료), 결제 전 취소, 반품 심사(1회), 결제 후 7일 자동 확정 | 포함 |
| 예치금·결제 | 회원 | 토스 예치금 충전, 지갑 → 홀딩 결제, 미사용 충전 건 취소, 반품 환불 | 포함 |
| 월 정산 | 판매자, 관리자 | 구매 확정 시 정산 후보 생성, 매월 10일 정산(Spring Batch), 수수료 10% 차감 지급, 재시도·수동 정산 | 포함 |

**회원/인증**

- 가입 순서는 이메일 중복 확인 → 6자리 인증코드(5분 유효) → 가입이며, 회원과 예치금 지갑을 한 트랜잭션으로 만든다
- 휴대폰 번호는 판매자에게만 받고, 휴대폰 본인인증 절차는 진행하지 않는다
- 로그인은 Access Token 30분, Refresh Token 14일(재발급 시 회전)이다
- 역할은 USER·SELLER·ADMIN이며 인증 실패는 401, 권한 부족은 403으로 구분한다
- 탈퇴는 진행 중인 주문, 미정산 금액, 예치금 잔액이 모두 0일 때만 가능하고, 탈퇴 시 개인정보를 비식별화한다
    - 예치금이 남아 있으면 탈퇴를 거절하고, 미사용 충전 건은 충전 취소로 환불받도록 안내한다
    - 탈퇴 시 시스템 차원의 자동 환불은 하지 않는다
- 회원 상태는 ACTIVE·DELETED를 쓴다

**판매자**

- 사업자번호·카테고리(뷰티/의류)·상호명·연락처·정산 계좌를 받아 신청하고, 관리자가 승인하면 기존 계정에 SELLER 권한이 더해진다
- 상태는 PENDING → APPROVED/REJECTED, 철회는 WITHDRAW_REQUESTED → WITHDRAWN이다
- 회원·사업자번호당 활성 신청은 1건만 허용하고, 판매자 기능은 요청마다 DB의 승인 상태를 다시 확인한다
- 판매 기능은 role이 SELLER이고 판매자 상태가 APPROVED일 때만 쓸 수 있다
- 판매자도 일반 회원처럼 구매할 수 있다. 단, 본인 상품은 장바구니에 담거나 주문할 수 없다
- 판매자 정산금은 해당 회원의 기존 예치금 지갑에 지급하며, 판매자는 정산받은 예치금으로 상품을 구매할 수 있다
- **판매자 스스로 철회:** 철회 요청(WITHDRAW_REQUESTED) → 관리자 승인 → WITHDRAWN → 판매 상품 전체 판매 중지
    - 미정산 금액이 있으면 철회를 요청할 수 없고, 없으면 일반 사용자로 전환한다(판매 불가, 구매 가능)
- **관리자 제재:** 관리자가 판매자를 바로 WITHDRAWN으로 전환한다
    - role은 SELLER로 두고 판매자 상태만 WITHDRAWN으로 바꾼다
    - 결제 완료(PAID)된 주문은 그대로 진행하고, 결제 대기(CREATED·PAYMENT_FAILED) 주문은 취소하고 재고를 복원한다
    - 판매자의 상품은 모두 판매 중지(STOPPED)하고, 정산은 HOLD로 두어 지급하지 않는다

**상품·카테고리·재고·검색**

- 상품 상태는 ON_SALE·SOLD_OUT·STOPPED·DELETED이며, 재고는 ON_SALE ↔ SOLD_OUT만 바꾼다
- 상품 등록 시 필수 항목은 상품명, 가격, 카테고리, 상품 설명이며 상품 이미지는 선택 항목이다
- 판매 중지는 판매자와 관리자만 할 수 있으며 중지 사유를 반드시 적는다
    - 판매자는 판매 중지를 요청하고, 관리자가 사유를 검토해 승인하거나 반려한다
    - 관리자는 사유를 적고 바로 판매 중지한다
    - 판매 중지 해제 기능은 제공하지 않는다
- 상품이 STOPPED·DELETED가 되면 그 뒤로는 장바구니 담기와 주문 생성이 불가능하다
    - 이미 결제된(PAID 이후) 주문은 그대로 진행하고, 결제 대기 주문은 취소하고 재고를 복원한다
- 재고는 증감량 + 사유로만 조정하고 모든 변화를 재고 이력에 남긴다(INIT, ADJUST, ORDER_DEDUCT, ORDER_RESTORE, RETURN_RESTORE)
- 카테고리는 대분류(뷰티/의류)-소분류 2단계이며 관리자만 관리한다
- 검색은 상품명 부분 일치, 카테고리 필터, 최신순·가격순 정렬, 품절 제외(기본 켜짐)를 지원한다

**장바구니·주문**

- 장바구니는 가격·재고를 보장하지 않고, 조회할 때마다 현재 가격·상태를 다시 계산해 예상 결제 금액을 보여 준다
- 장바구니에는 여러 판매자의 상품을 담을 수 있고, 수량은 1~99이며 같은 상품은 수량을 합산한다
- 상품 추가·삭제·수량 변경은 장바구니에서만 한다. 주문이 생긴 뒤에는 결제하거나 전체 취소하는 것만 할 수 있다
- 장바구니 항목은 주문 결제가 완료되는 시점에 삭제한다(주문에 포함된 항목만)
- 결제 대기 주문(CREATED·PAYMENT_FAILED)에 들어 있는 장바구니 항목으로는 새 주문을 만들 수 없다
- 주문 생성 때 가격·상품·판매자 정보를 스냅샷으로 고정하고 재고를 차감(예약, ORDER_DEDUCT)한다
- 주문 상태는 CREATED, PAYMENT_FAILED, PAID, CONFIRMED, CANCELED, RETURN_REQUESTED, RETURNED 7개이며, 판매자별로 나누지 않고 주문서 하나에 하나의 상태만 둔다

*결제·만료*

- 주문 만료 시간은 주문 생성 시점부터 15분이며, 그 안에 결제하지 않으면 취소하고 재고를 복원한다
    - 만료 처리는 만료 시각 1분 후부터 CREATED·PAYMENT_FAILED 주문만 취소하고, 취소에 성공한 경우에만 재고를 복원(ORDER_RESTORE)한다
- 결제는 주문이 CREATED 또는 PAYMENT_FAILED이고 만료 시각 전일 때만 성공하며, 예치금 이동과 주문 상태 변경을 한 트랜잭션으로 처리한다
- 결제와 주문 만료가 겹치면 주문 상태를 먼저 변경한 처리만 반영한다
- 잔액이 부족하면 결제 건을 FAILED_INSUFFICIENT_BALANCE로 기록하고, 주문은 CREATED로 유지한다
- PAYMENT_FAILED는 결제 진행 중 시스템 오류가 발생한 경우에만 남기며(결제 건 FAILED_SYSTEM_ERROR), 실패 기록은 결제 트랜잭션과 별도의 트랜잭션으로 저장한다. 만료 시각 전까지 같은 주문을 다시 결제할 수 있다
- 주문 만료와 충전 만료는 각자 독립적으로 처리한다. 주문이 만료돼도 진행 중인 충전은 취소하지 않는다
- 결제 중 잔액이 부족해 충전으로 이동하면, 충전 화면에 주문의 남은 시간을 보여 준다
- 충전을 마쳤을 때 주문이 이미 만료됐다면, 충전 금액은 예치금에 반영하고 주문 만료를 안내한 뒤 장바구니로 이동한다

*주문 취소*

- 주문 취소는 CREATED·PAYMENT_FAILED(결제 전) 상태에서만 구매자가 할 수 있고, 주문 전체 단위이며 부분 취소는 불가능하다
- 결제 전 취소이므로 환불은 없고 재고만 복원(ORDER_RESTORE)한다
- 결제 후(PAID)에는 취소할 수 없고 반품 요청만 할 수 있다

*반품*

- 반품은 PAID 상태(구매 확정 전)에서만, 주문당 1회, 주문 전체 단위로 요청할 수 있다. 구매 확정 후에는 반품을 요청할 수 없다
- 관리자가 승인하면 RETURNED가 되고 홀딩 금액을 구매자 예치금으로 환불한다
    - 승인 시 관리자가 주문 항목마다 재고 복원 여부(restoreStock)를 고른다
    - 복원을 선택하면 수량 전체를 RETURN_RESTORE로 되돌리고, 선택하지 않으면(파손·사용 등) 재고 이력을 남기지 않는다
    - 복원 시 SOLD_OUT 상품은 ON_SALE로 바뀌고, STOPPED 상품은 재고만 늘고 상태는 유지된다. DELETED 상품은 복원할 수 없다
- 관리자가 거절하면 PAID로 돌아간다. 이때 결제 완료 후 7일이 이미 지났으면 즉시 구매 확정(CONFIRMED)한다

*구매 확정*

- 구매 확정은 결제 완료(PAID) 후 구매자의 수동 확정 또는 결제 완료 시각 + 7일 후 자동 확정이다
- RETURN_REQUESTED 상태의 주문은 자동 확정 대상에서 제외한다
- 확정되면 주문 항목(판매자) 단위로 정산 후보가 만들어진다

**예치금·결제**

- 모듈별 원장 기록 책임
    - 충전 모듈: 토스페이먼츠 승인·취소 통신, 충전 상태 관리 — CHARGE(충전 완료), CHARGE_CANCEL(미사용 충전 취소)
    - 결제 모듈: 주문 결제 상태 관리, 지갑 ↔ 홀딩 이동 — PAYMENT(결제, 지갑 → 홀딩), REFUND(반품 승인, 홀딩 → 구매자 지갑)
    - 정산 모듈: SETTLEMENT(홀딩 → 판매자 지갑), FEE(홀딩 → 관리자 수수료)
- 충전은 서버가 금액·주문번호를 저장해 두고 토스 승인 전후에 금액·주문번호를 검증한다. 같은 결제키는 한 번만 반영한다
- 예치금 충전은 10,000원 단위로만 가능하며, 1회 최소 10,000원, 최대 1,000,000원이다
- 결제 시 잔액이 부족하면 402 INSUFFICIENT_BALANCE를 반환하고, 부족 금액을 10,000원 단위로 올린 금액(예: 18,000원 부족 시 20,000원)으로 충전을 안내한다. 충전을 마치면 주문 만료 전까지 같은 주문으로 다시 결제한다
- 결제는 지갑 행을 잠근 뒤 지갑 → 홀딩 이동을 한 트랜잭션으로 처리하고, 모든 잔액 변화는 원장(예치금 거래 내역)에 멱등 키와 함께 기록한다
- 홀딩은 주문서 1개당 1개를 만든다
- 결제 금액은 오래된 충전 건부터 차감하고, 충전 건마다 남은 금액과 충전 건별 차감 내역을 기록한다
- 미사용 충전 취소: 남은 금액이 처음 충전 금액과 같은 충전 건만 토스 취소로 전액 환불할 수 있다. 주문 환불이나 정산으로 들어온 예치금은 토스 취소 대상이 아니다
- 환불은 홀딩 금액을 구매자 예치금으로 돌려주는 것(REFUND)이며, 반품 승인 시에만 발생한다. 충전 취소와는 구분한다
    - 흐름: 주문 → 결제 → 반품 요청 → 관리자 반품 승인 → 환불(REFUND)
    - 반품으로 돌아온 예치금은 충전 건의 남은 금액을 복원하지 않는다
- 부분 취소는 불가능하다
- 결제 상태 PROCESSING은 일단 유지하고, 개발 중 필요 없다고 판단되면 제거한다

**월 정산**

- 정산 대상은 구매확정(CONFIRMED)된 주문이다. 한 주문에 여러 판매자가 섞이므로 주문 항목(판매자) 단위로 정산 후보를 만들고 판매자별로 나눠 정산한다
- 정산 기간은 구매 확정일(KST)이 속한 달이고, 다음 달 10일 자정에 일괄 지급한다(지급 시각은 추후 변경될 수 있음)
- 수수료율은 판매 대금의 10%(VAT 포함)이고, 판매자에게는 나머지를 예치금으로 입금한다
    - 수수료 = 판매가 × 수수료율 ÷ 100 (곱셈을 먼저 하고, 품목별로 소수점 아래는 버림)
    - 지급액 = 판매가 − 수수료 (비율로 따로 계산하지 않고 빼기만 함)
- 판매자 1명을 처리 단위 1개로 처리하고, ① 선점(PROCESSING 표시 후 커밋) → ② 예치금 송금 → ③ 결과 기록(PAID/FAILED) 3구간으로 나눈다
- 실패한 정산서는 FAILED로 두고, 최초 시도 1회 + 10분 간격 재시도 2회, 총 3회까지 시도한다
- 총 3회 모두 실패하거나 재시도 불가 사유(판매자 예치금 계좌 없음·정지, 금액 불일치 등)가 있으면 MANUAL_REQUIRED가 되고 관리자가 수동 정산한다
- 재시도 전에는 정산 DB만 보지 않고 예치금 원장에 실제 송금 여부를 먼저 조회한다
    - 처리됨 + 금액 같음 → 송금 없이 PAID, 처리됨 + 금액 다름 → 관리자에게 넘김, 기록 없음 → 같은 키로 송금, 조회 실패 → 실패 기록 후 다음 재시도
- 10일 자동 정산 발행이 실패하면 관리자가 수동 정산을 요청한다. 관리자 정산 페이지에는 정산 리스트와 실패 사유를 보여 준다
- HOLD 상태의 정산서는 정산하지 않는다(관리자 제재로 철회된 판매자 등)

**관리자**

- 단일 관리자 계정(시드 생성)이 회원·판매자·상품·결제·정산을 조회하고 판매자 승인·철회 승인·제재, 상품 판매 중지·판매 중지 요청 심사, 반품 승인을 처리한다
- 실패 거래(충전 정체, 결제 실패, 환불 FAILED, 정산 FAILED·MANUAL_REQUIRED 등)는 조회만 하고, 수동 실행은 월 정산 재처리만 허용한다
- 모든 변경 행위는 감사 로그에 남긴다

### 1.4 상태 코드

| 대상 | 상태 |
| --- | --- |
| 회원 | ACTIVE / DELETED (INACTIVE는 미구현) |
| 판매자 | PENDING / APPROVED / REJECTED / WITHDRAW_REQUESTED / WITHDRAWN |
| 상품 | ON_SALE / SOLD_OUT / STOPPED / DELETED |
| 판매 중지 요청 | PENDING / APPROVED / REJECTED |
| 재고 이력 사유 | INIT / ADJUST / ORDER_DEDUCT / ORDER_RESTORE / RETURN_RESTORE |
| 주문 | CREATED(주문 생성, 결제 대기) / PAYMENT_FAILED / PAID / CONFIRMED / CANCELED / RETURN_REQUESTED / RETURNED |
| 결제 | PROCESSING / PAID / REFUNDED / FAILED_INSUFFICIENT_BALANCE / FAILED_ORDER_EXPIRED / FAILED_SYSTEM_ERROR |
| 홀딩 | HELD / RELEASED / SETTLED |
| 환불 | 상태 REQUESTED / COMPLETED / FAILED, 유형 RETURN (CANCEL은 향후 대비, 현재 미사용) |
| 충전 | READY / PROCESSING / DONE / CANCELED / FAILED_USER_CANCEL / FAILED_PG_DECLINED / FAILED_AMOUNT_MISMATCH / FAILED_EXPIRED / FAILED_SYSTEM_ERROR |
| 예치금 거래 유형 | CHARGE / CHARGE_CANCEL / PAYMENT / REFUND / SETTLEMENT / FEE |
| 정산 후보 | READY / INCLUDED / EXCLUDED |
| 정산서 | READY / PROCESSING / PAID / FAILED / MANUAL_REQUIRED / HOLD |

## 2. 아키텍처 다이어그램

v1은 Spring Boot 단일 애플리케이션이지만 모듈 경계를 서비스 경계처럼 지켜, 나중에 모듈 단위로 MSA 분리할 수 있게 설계한다. 모듈은 member · product · order · payment · settlement 5개이며, 각 모듈은 `in / app / domain / out` 헥사고날 레이어로 나눈다. 모듈끼리는 `shared`의 이벤트·DTO로만 의존한다.

![시스템 아키텍처](palette_system_architecture.png)

- 클라이언트 요청은 모두 보안 필터(JWT·역할 검사)를 거쳐 각 모듈 컨트롤러로 들어간다
- 주문이 중심이다. 주문 생성 때 상품 모듈에 재고 차감을 요청하고(같은 트랜잭션), 결제 때 결제 모듈을 호출해 지갑 → 홀딩 이동과 주문 PAID 변경을 한 트랜잭션으로 처리한다
- 잔액이 모자라면 결제 모듈이 402 INSUFFICIENT_BALANCE로 응답하고 결제 건을 FAILED_INSUFFICIENT_BALANCE로 남긴다. 주문은 CREATED로 유지되고 구매자는 충전 후 같은 주문을 다시 결제한다. 충전 승인·취소는 토스페이먼츠를 쓴다
- 반품 승인은 Outbox에 OrderReturned로 기록되고, 결제 모듈이 이를 받아 홀딩 금액을 구매자 지갑으로 환불한다
- 구매 확정은 Outbox에 OrderConfirmed로 기록되고, 정산 모듈이 이를 받아 주문 항목별 정산 후보를 만든 뒤 매월 10일 판매자 정산 대금과 관리자 수수료를 각 지갑에 입금한다
- 상품 판매 중지·삭제와 판매자 제재는 주문 모듈에 결제 대기 주문 취소를 요청한다
- 외부 호출(토스, 메일)은 DB 트랜잭션 밖에서 하고, 실패는 스케줄러의 복구·대사 작업이 마무리한다

## 3. API 명세서

REST API를 모듈별로 나눠 정의한다(외부 77개, 모듈 간 내부 6개). 요청·응답 필드의 세부 스키마는 구현하면서 OpenAPI(Swagger)로 관리한다.

### 3.0 공통 규칙

- Base URL: `/api/v1`, 요청·응답은 JSON(파일 업로드만 multipart)
- 인증: `Authorization: Bearer <Access Token>`. 판매자 API는 `/seller/**`(SELLER + APPROVED), 관리자 API는 `/admin/**`(ADMIN) 경로로 묶어 권한을 검사한다
- 응답 형식: 성공·실패 모두 `RsData { resultCode, msg, data }`로 감싼다. HTTP 상태는 `resultCode` 앞자리와 같다. 성공은 모두 200이고, 실패 상태는 `GlobalExceptionHandler`가 맞춘다
  - 성공 `resultCode`는 `200-{순번}`이다(조회·생성·처리 모두 `200-1`). 생성도 201이 아니라 200으로 응답한다. `data`에는 응답 DTO를 담는다
  - 실패 `resultCode`는 `{HTTP 상태}-{오류 이름}`이다(예: `402-INSUFFICIENT_BALANCE`). `data`에는 추가 정보가 필요한 오류만 담고(입력 검증 필드 오류, 잔액 부족의 부족액 등) 없으면 `null`이다
  - 인증 실패 401, 권한 부족 403, 상태 충돌 409, 잔액 부족 402. 아래 표의 "주요 오류" `409 EMAIL_DUPLICATED`는 `resultCode` `409-EMAIL_DUPLICATED`를 뜻한다
  - 예외 처리 방식은 `palette_exception_handling.md`를 따른다
- 목록: `page`(0부터), `size`(기본 20, 최대 50), 기본 최신순
- 돈이 움직이는 쓰기 API(충전·결제·충전 취소)는 `Idempotency-Key` 헤더를 받아 24시간 같은 응답을 돌려준다
- 금액은 원 단위 정수, 시각은 KST ISO-8601

### 3.1 회원, 판매자

| 경로 | 메서드 | 권한 | 설명 | 주요 오류 |
| --- | --- | --- | --- | --- |
| /auth/email/check | POST | 비회원 | 이메일 중복 확인 | 409 EMAIL_DUPLICATED |
| /auth/email/code | POST | 비회원 | 인증코드 발송 | 429 TOO_MANY_REQUESTS |
| /auth/email/verify | POST | 비회원 | 인증코드 확인 | 400 CODE_INVALID, 400 CODE_EXPIRED |
| /auth/signup | POST | 비회원 | 회원가입 + 지갑 생성 | 400 EMAIL_NOT_VERIFIED, 409 EMAIL_DUPLICATED |
| /auth/login | POST | 비회원 | 로그인 → Access·Refresh | 401 LOGIN_FAILED |
| /auth/refresh | POST | 회원 | 토큰 재발급(회전) | 401 AUTH_TOKEN_EXPIRED |
| /auth/logout | POST | USER | 로그아웃 | 401 |
| /users/me | GET | USER | 내 정보 조회 | 401 |
| /users/me | DELETE | USER | 회원 탈퇴(주문·미정산·잔액 0일 때만) | 409 WITHDRAWAL_NOT_ALLOWED |

| 경로 | 메서드 | 권한 | 설명 | 주요 오류 |
| --- | --- | --- | --- | --- |
| /sellers | POST | USER | 판매자 신청(사업자번호, 카테고리, 상호명, 휴대폰, 정산 계좌) | 400 VALIDATION_FAILED, 409 SELLER_DUPLICATED |
| /sellers/me | GET | USER | 내 판매자 정보·신청 상태(계좌는 끝 4자리) | 404 |
| /seller/me | PATCH | SELLER | 상호명·연락처·정산 계좌 수정 | 403 |
| /seller/me/withdrawal | POST | SELLER | 판매자 철회 요청 | 409 UNSETTLED_AMOUNT_EXISTS |

### 3.2 상품

| 경로 | 메서드 | 권한 | 설명 | 주요 오류 |
| --- | --- | --- | --- | --- |
| /products | GET | 모두 | 상품 검색·목록(`keyword`, `categoryId`, `sort`=LATEST·PRICE_ASC·PRICE_DESC, `excludeSoldOut`=true) | 400 |
| /products/{productId} | GET | 모두 | 상품 상세(가격, 설명, 상호명, 품절 여부, 재고 5개 이하면 남은 수량) | 404 |
| /categories | GET | 모두 | 카테고리 트리(대분류-소분류) | - |
| /seller/products | POST | SELLER | 상품 등록(필수: 상품명·가격·소분류·설명·초기 재고, 선택: 이미지) | 400, 403 |
| /seller/products | GET | SELLER | 본인 상품 목록(상태 필터) | - |
| /seller/products/{productId} | PATCH | SELLER | 상품 수정(재고 제외) | 403, 404, 409 PRODUCT_NOT_EDITABLE |
| /seller/products/{productId} | DELETE | SELLER | 상품 삭제(소프트 삭제) | 403, 404 |
| /seller/products/{productId}/images | POST | SELLER | 상품 이미지 업로드 | 400 |
| /seller/products/{productId}/stock-adjustments | POST | SELLER | 재고 조정(증감량 + 사유) | 400 STOCK_NEGATIVE |
| /seller/products/{productId}/stock-history | GET | SELLER | 재고 이력 조회 | 403 |
| /seller/products/{productId}/stop-requests | POST | SELLER | 판매 중지 요청(사유 필수) → 관리자 검토 | 409 STOP_REQUEST_PENDING |

### 3.3 장바구니·주문

| 경로 | 메서드 | 권한 | 설명 | 주요 오류 |
| --- | --- | --- | --- | --- |
| /cart | GET | USER | 장바구니 조회(항목별 상태, 가격 변동, 예상 결제 금액, `orderable`) | - |
| /cart/items | POST | USER | 상품 담기(수량 1~99, 같은 상품은 합산) | 400 STOCK_EXCEEDED, 400 SELF_PURCHASE_NOT_ALLOWED, 409 PRODUCT_NOT_ON_SALE |
| /cart/items/{itemId} | PATCH | USER | 수량 변경·선택 여부 변경 | 400 STOCK_EXCEEDED |
| /cart/items/selection | PATCH | USER | 전체 선택·해제 | - |
| /cart/items | DELETE | USER | 항목 단건·다건 삭제(`itemIds`) | - |

| 경로 | 메서드 | 권한 | 설명 | 주요 오류 |
| --- | --- | --- | --- | --- |
| /orders | POST | USER | 주문 생성(장바구니 항목 id, 예상 금액) → CREATED, `expiresAt`(생성 + 15분) | 400 OUT_OF_STOCK, 409 PRICE_CHANGED, 409 PRODUCT_NOT_ON_SALE, 409 CART_ITEM_IN_PENDING_ORDER, 400 SELF_PURCHASE_NOT_ALLOWED |
| /orders | GET | USER | 내 주문 목록(상태·기간 필터) | - |
| /orders/{orderId} | GET | USER | 주문 상세(항목 스냅샷, 결제 시도와 실패 사유, 남은 시간) | 403, 404 |
| /orders/{orderId}/payment | POST | USER | 예치금 결제(CREATED·PAYMENT_FAILED, 만료 전만) → PAID, 장바구니 항목 삭제 | 402 INSUFFICIENT_BALANCE(부족액·추천 충전액·남은 시간), 409 ORDER_EXPIRED, 409 ORDER_STATE_INVALID |
| /orders/{orderId}/cancel | POST | USER | 결제 전 주문 전체 취소(재고 복원, 환불 없음) | 409 ORDER_STATE_INVALID |
| /orders/{orderId}/confirm | POST | USER | 구매 확정 → CONFIRMED, 정산 후보 생성 | 409 ORDER_STATE_INVALID |
| /orders/{orderId}/returns | POST | USER | 반품 요청(PAID만, 주문당 1회, 사유 코드 + 상세) → RETURN_REQUESTED | 409 ORDER_STATE_INVALID, 409 RETURN_ALREADY_REQUESTED |
| /seller/order-items | GET | SELLER | 본인 상품이 들어간 주문 항목 조회 | - |

### 3.4 예치금·결제

| 경로 | 메서드 | 권한 | 설명 | 주요 오류 |
| --- | --- | --- | --- | --- |
| /wallet | GET | USER | 잔액과 홀딩 중 금액 조회 | - |
| /wallet/transactions | GET | USER | 거래 내역(원장, 유형·기간 필터) | - |
| /topups | POST | USER | 충전 준비(금액 10,000원 단위, 1만~100만 원) → `orderId`(TOPUP-…), `amount` | 400 TOPUP_AMOUNT_INVALID |
| /topups/confirm | POST | USER | 토스 성공 리다이렉트 후 승인(`paymentKey`, `orderId`, `amount`) → 지갑 반영. 토스 응답 시간 초과 시 `200-1` + `data.status: PROCESSING`(결과 확인 중)으로 응답하고 대사 스케줄러가 확정 | 400 AMOUNT_MISMATCH, 409 TOPUP_ALREADY_PROCESSED, 502 EXTERNAL_SERVICE_ERROR |
| /topups/{topupId}/fail | POST | USER | 토스 실패 리다이렉트 기록(사유 코드) | 409 |
| /topups | GET | USER | 충전 목록(남은 금액, 취소 가능 여부) | - |
| /topups/{topupId} | GET | USER | 충전 건 상세·차감 내역 | 404 |
| /topups/{topupId}/cancel | POST | USER | 미사용 충전 건 전액 취소(토스 취소) | 409 TOPUP_ALREADY_USED |

### 3.5 정산

| 경로 | 메서드 | 권한 | 설명 | 주요 오류 |
| --- | --- | --- | --- | --- |
| /seller/settlements | GET | SELLER | 본인 월 정산서 목록(정산월·상태) | - |
| /seller/settlements/{settlementId} | GET | SELLER | 정산서 상세(판매 대금, 수수료, 지급액, 항목별 내역) | 403, 404 |

### 3.6 관리자

| 경로 | 메서드 | 담당 모듈 | 권한 | 설명 | 주요 오류 |
| --- | --- | --- | --- | --- | --- |
| /admin/users | GET | 회원 | ADMIN | 회원 목록(이메일·상태·가입일) | 403 |
| /admin/users/{userId} | GET | 회원 | ADMIN | 회원 상세(역할, 판매자 상태, 지갑, 최근 주문) | 404 |
| /admin/sellers | GET | 회원 | ADMIN | 판매자 목록(PENDING 우선) | - |
| /admin/sellers/{sellerId} | GET | 회원 | ADMIN | 판매자 상세 | 404 |
| /admin/sellers/{sellerId}/approve | POST | 회원 | ADMIN | 판매자 승인 → SELLER 권한 부여 | 409 SELLER_STATE_INVALID |
| /admin/sellers/{sellerId}/reject | POST | 회원 | ADMIN | 판매자 반려(사유 필수) | 409 SELLER_STATE_INVALID |
| /admin/sellers/{sellerId}/withdrawal/approve | POST | 회원 | ADMIN | 철회 승인 → WITHDRAWN, 상품 전체 판매 중지 | 409 |
| /admin/sellers/{sellerId}/withdrawal/reject | POST | 회원 | ADMIN | 철회 거절 | 409 |
| /admin/sellers/{sellerId}/sanction | POST | 회원 | ADMIN | 관리자 제재(사유) → WITHDRAWN, 결제 대기 주문 취소, 상품 STOPPED, 정산 HOLD | 409 |
| /admin/products | GET | 상품 | ADMIN | 전체 상품(판매자·상태·카테고리) | - |
| /admin/products/{productId}/stop | POST | 상품 | ADMIN | 즉시 판매 중지(사유 필수, 해제 없음) | 409 |
| /admin/product-stop-requests | GET | 상품 | ADMIN | 판매 중지 요청 목록 | - |
| /admin/product-stop-requests/{requestId}/approve | POST | 상품 | ADMIN | 중지 요청 승인 → STOPPED | 409 |
| /admin/product-stop-requests/{requestId}/reject | POST | 상품 | ADMIN | 중지 요청 반려(사유) | 409 |
| /admin/categories | POST | 상품 | ADMIN | 카테고리 추가 | 409(같은 부모 아래 이름 중복) |
| /admin/categories/{categoryId} | PATCH | 상품 | ADMIN | 이름·정렬 순서·사용 여부 변경 | 409 |
| /admin/returns | GET | 주문 | ADMIN | 반품 요청 목록 | - |
| /admin/returns/{orderId}/approve | POST | 주문 | ADMIN | 반품 승인(주문 항목별 `restoreStock`) → RETURNED + 환불 | 409 ORDER_STATE_INVALID, 409 PRODUCT_DELETED |
| /admin/returns/{orderId}/reject | POST | 주문 | ADMIN | 반품 거절 → PAID 복귀(결제 후 7일 지났으면 즉시 CONFIRMED) | 409 ORDER_STATE_INVALID |
| /admin/payments | GET | 결제 | ADMIN | 충전·결제·환불 처리 상태 조회(주문번호·회원·기간) | - |
| /admin/failed-transactions | GET | 결제 | ADMIN | 실패 거래 조회(충전 정체, 결제 실패, 환불 FAILED, 정산 FAILED·MANUAL_REQUIRED) | - |
| /admin/settlements/preview | GET | 정산 | ADMIN | 정산월별 판매자 정산 대상·예상 금액 | 400 |
| /admin/settlements/runs | POST | 정산 | ADMIN | 월 정산 수동 실행(`settleMonth`, 종료된 달만) | 409 SETTLEMENT_RUNNING |
| /admin/settlements/runs | GET | 정산 | ADMIN | 정산 Job 실행 결과(성공·실패 건수, 지급·수수료 총액) | - |
| /admin/settlements | GET | 정산 | ADMIN | 판매자별 정산서 목록·상세(상태, 실패 사유, 재시도 횟수) | - |
| /admin/settlements/{settlementId}/retry | POST | 정산 | ADMIN | MANUAL_REQUIRED 정산서 수동 재처리 | 409 |
| /admin/settlements/{settlementId}/hold | POST | 정산 | ADMIN | 정산 보류 → HOLD | 409 |
| /admin/settlements/{settlementId}/release | POST | 정산 | ADMIN | 보류 해제 → READY | 409 |
| /admin/settlement-candidates/{candidateId}/exclude | POST | 정산 | ADMIN | 정산 후보 제외(사유) → EXCLUDED | 409 |
| /admin/audit-logs | GET | 공통 | ADMIN | 감사 로그 조회 | - |

### 3.7 모듈 간 내부 API

| 호출 | 제공 모듈 | 설명 |
| --- | --- | --- |
| `deductStock(items, orderItemIds)` | 상품 | ORDER_DEDUCT, 상품 ID 순으로 잠금 |
| `restoreStock(items, reason)` | 상품 | ORDER_RESTORE / RETURN_RESTORE |
| `pay(orderId, buyerId, amount, idempotencyKey)` | 결제 | 지갑 → 홀딩, 결과 PAID / FAILED_* |
| `cancelPendingOrders(productIds 또는 sellerId)` | 주문 | 판매 중지·삭제·제재 시 결제 대기 주문 취소 |
| `transfer(SETTLEMENT-{id}, sellerId, payout, fee)` | 결제(예치금) | 홀딩 → 판매자·관리자 지갑, 멱등 |
| `GET /internal/cash/transfers/SETTLEMENT-{id}` | 결제(예치금) | 송금 여부·금액 조회(없으면 404) |

## 4. 데이터 모델

PostgreSQL 기준 테이블 23개로 설계했다. 상세 ERD와 정책을 테이블 제약으로 담은 방식은 `docs/erd/erd.md`에 둔다.

| 모듈 | 테이블 |
| --- | --- |
| member | member, seller |
| product | category, product, product_image, product_stop_request, stock_history |
| order | cart, cart_item, market_order, market_order_item, order_status_history |
| payment | wallet, wallet_transaction, topup, topup_usage, payment, wallet_holding, payment_refund |
| settlement | settlement_candidate, settlement |
| 공통 | outbox_event, audit_log |

이메일 인증코드(5분)와 Refresh Token(14일)은 Redis에 저장하고, 월 정산 Job 실행 결과는 Spring Batch 메타 테이블을 쓴다.