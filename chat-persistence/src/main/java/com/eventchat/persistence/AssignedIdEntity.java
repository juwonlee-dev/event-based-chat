package com.eventchat.persistence;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * 복합 키처럼 ID를 직접 채워 저장하는 엔티티의 기반.
 * Spring Data는 ID가 채워져 있으면 기존 행으로 보고 merge(SELECT 후 INSERT)를 하므로,
 * 새로 만든 객체임을 직접 알려 바로 persist하게 한다.
 */
@MappedSuperclass
public abstract class AssignedIdEntity<ID> implements Persistable<ID> {

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
