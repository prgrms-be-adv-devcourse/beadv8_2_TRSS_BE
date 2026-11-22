package com.backend.boundedContext.product.app;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;

/**
 * 쿼리 수를 세는 테스트용 (측정 테스트, 상세 쿼리 수 테스트). Hibernate가 SQL을 준비할 때마다 부르는 스레드에 기록한다.
 * Hibernate 통계는 앱 전체 값이라 스케줄러 쿼리가 섞이므로, {@link #record}로 켠 스레드의 쿼리만 센다.
 * 테스트 설정(application.properties)의 {@code hibernate.session_factory.statement_inspector}에 이 클래스 이름을 줘 Hibernate가 만든다.
 */
public class ThreadQueryCounter implements StatementInspector {
    private static final ThreadLocal<List<String>> RECORDED = new ThreadLocal<>();

    @Override
    public String inspect(String sql) {
        List<String> recorded = RECORDED.get();
        if (recorded != null) recorded.add(sql);
        return sql;
    }

    /** 이 스레드에서 action을 실행하는 동안 준비된 SQL */
    static List<String> record(Runnable action) {
        List<String> recorded = new ArrayList<>();
        RECORDED.set(recorded);
        try {
            action.run();
        } finally {
            RECORDED.remove();
        }
        return recorded;
    }
}
