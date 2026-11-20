package com.backend.boundedContext.payment.out.pg;

import java.util.Map;

/**
 * 결제창을 띄우는 데 필요한 값. PG마다 달라 어댑터가 만듦
 * - url: 결제창 주소 (프론트가 params를 이 주소로 보내거나 PG SDK에 넘김)
 * - params: 결제창 요청 값 (헥토는 금액 암호화·해시를 서버에서 만들어야 해서 여기 담음)
 */
public record PgCheckout(
        String url,
        Map<String, String> params
) {}
