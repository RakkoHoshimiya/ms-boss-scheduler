package com.gmsr.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 群友（玩家）。 */
@Entity
@Table(name = "player")
public class Player extends AuditedEntity {

    /** 登录账号用的 QQ 号，唯一。 */
    @Column(length = 20, unique = true)
    private String qq;

    /** 显示名称（群里怎么叫）。 */
    @Column(name = "display_name", nullable = false, unique = true, length = 64)
    private String nickname;

    @Column(length = 255)
    private String remark;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public String getQq() {
        return qq;
    }

    public void setQq(String qq) {
        this.qq = qq;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
