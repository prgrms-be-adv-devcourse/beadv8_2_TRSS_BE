package com.backend.global.outbox;

import com.backend.shared.event.DomainEvent;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * eventType(클래스 단순 이름) → 이벤트 클래스.
 * 릴레이가 outbox의 JSON payload를 원래 이벤트 record로 되돌릴 때 쓴다.
 */
@Component
public class OutboxEventTypes {

    private static final String BASE_PACKAGE = "com.backend.shared";

    private final Map<String, Class<? extends DomainEvent>> typesByName;

    public OutboxEventTypes() {
        this.typesByName = scan();
    }

    public Optional<Class<? extends DomainEvent>> find(String eventType) {
        return Optional.ofNullable(typesByName.get(eventType));
    }

    private static Map<String, Class<? extends DomainEvent>> scan() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(DomainEvent.class));

        Map<String, Class<? extends DomainEvent>> types = new HashMap<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<? extends DomainEvent> type = ClassUtils.resolveClassName(
                    candidate.getBeanClassName(), OutboxEventTypes.class.getClassLoader()).asSubclass(DomainEvent.class);
            Class<? extends DomainEvent> duplicate = types.put(type.getSimpleName(), type);
            if (duplicate != null) {
                throw new IllegalStateException(
                        "이벤트 클래스 이름이 겹칩니다: " + duplicate.getName() + ", " + type.getName());
            }
        }
        return Map.copyOf(types);
    }
}
