package com.backend.boundedContext.product.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static jakarta.persistence.FetchType.LAZY;

/**
 * 카테고리. 대분류(부모 없음) - 소분류(부모 = 대분류) 2단계다.
 * 대분류는 초기 데이터로만 만들고 수정할 수 없다.
 */
@Entity
@Table(name = "category", uniqueConstraints = {
        @UniqueConstraint(name = "uk_category_parent_name", columnNames = {"parent_id", "name"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category extends BaseIdAndTime {

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    private Category(Category parent, String name, int sortOrder) {
        this.parent = parent;
        this.name = name.strip();
        this.sortOrder = sortOrder;
        this.active = true;
    }

    /** 대분류를 만든다. 초기 데이터에서만 쓴다. */
    public static Category createRoot(String name, int sortOrder) {
        return new Category(null, name, sortOrder);
    }

    /** 소분류를 만든다. 부모는 대분류여야 한다. */
    public static Category createChild(Category parent, String name, int sortOrder) {
        if (!parent.isRoot()) {
            throw new DomainException(ProductErrorCode.CATEGORY_DEPTH_EXCEEDED);
        }
        return new Category(parent, name, sortOrder);
    }

    public boolean isRoot() {
        return parent == null;
    }

    /** 대분류 1, 소분류 2 */
    public int getDepth() {
        return isRoot() ? 1 : 2;
    }

    /** 수정할 수 있는 카테고리인지 확인한다. 대분류면 예외. */
    public void checkModifiable() {
        if (isRoot()) {
            throw new DomainException(ProductErrorCode.CATEGORY_NOT_EDITABLE);
        }
    }

    /** null이 아닌 값만 반영한다. */
    public void modify(String name, Integer sortOrder, Boolean active) {
        checkModifiable();
        if (name != null) this.name = name.strip();
        if (sortOrder != null) this.sortOrder = sortOrder;
        if (active != null) this.active = active;
    }
}
