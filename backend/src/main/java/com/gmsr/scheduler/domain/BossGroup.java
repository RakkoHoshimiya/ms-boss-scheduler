package com.gmsr.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** BOSS 分组，例如「原初世界」「神秘河」。 */
@Entity
@Table(name = "boss_group")
public class BossGroup extends AuditedEntity {

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(length = 255)
    private String note;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
