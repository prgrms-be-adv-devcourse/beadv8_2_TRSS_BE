package com.backend.boundedContext.payment.out.pg;

// 결제 수단. PG마다 다른 표기를 하나로 맞춤 (충전 결제창에 있는 수단만 둠)
public enum PgPaymentMethod {
    CARD,       // 신용·체크카드
    EASY_PAY,   // 간편결제 (토스페이·페이코·카카오페이·네이버페이)
    TRANSFER,   // 계좌이체 (퀵계좌이체)
    OTHER       // 그 외·알 수 없음 (가상계좌·휴대폰 등 결제창에 없는 수단)
}
