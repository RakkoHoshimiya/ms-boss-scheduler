package com.gmsr.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 某个群友的一个角色：职业 + 战力。 */
@Entity
@Table(name = "character")
public class GameCharacter extends AuditedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(nullable = false, length = 32)
    private String job;

    /** 战力，单位「万」。 */
    @Column(nullable = false)
    private int power;

    @Column(name = "main_char", nullable = false)
    private boolean mainChar;

    @Column(length = 255)
    private String note;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public Player getPlayer() {
        return player;
    }

    public void setPlayer(Player player) {
        this.player = player;
    }

    public String getJob() {
        return job;
    }

    public void setJob(String job) {
        this.job = job;
    }

    public int getPower() {
        return power;
    }

    public void setPower(int power) {
        this.power = power;
    }

    public boolean isMainChar() {
        return mainChar;
    }

    public void setMainChar(boolean mainChar) {
        this.mainChar = mainChar;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
