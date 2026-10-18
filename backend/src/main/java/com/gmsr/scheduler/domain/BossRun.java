package com.gmsr.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 车次：某个 BOSS 的一次组队。 */
@Entity
@Table(name = "boss_run")
public class BossRun extends AuditedEntity {

    public enum Status {
        PLANNED, DONE, CANCELED
    }

    /** 所属排班周期（周四 ~ 下周三）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "week_id")
    private WeekSchedule week;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "boss_id", nullable = false)
    private Boss boss;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private BossTier tier = BossTier.NONE;

    @Column(name = "run_index", nullable = false)
    private int runIndex = 1;

    /** 约定时间，自由文本，例如「周五 21:00」。 */
    @Column(length = 32)
    private String scheduled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PLANNED;

    @Column(length = 255)
    private String note;

    public WeekSchedule getWeek() {
        return week;
    }

    public void setWeek(WeekSchedule week) {
        this.week = week;
    }

    public Boss getBoss() {
        return boss;
    }

    public void setBoss(Boss boss) {
        this.boss = boss;
    }

    public BossTier getTier() {
        return tier;
    }

    public void setTier(BossTier tier) {
        this.tier = tier;
    }

    public int getRunIndex() {
        return runIndex;
    }

    public void setRunIndex(int runIndex) {
        this.runIndex = runIndex;
    }

    public String getScheduled() {
        return scheduled;
    }

    public void setScheduled(String scheduled) {
        this.scheduled = scheduled;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
