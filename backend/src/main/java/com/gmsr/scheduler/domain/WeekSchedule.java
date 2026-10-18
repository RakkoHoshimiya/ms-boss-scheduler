package com.gmsr.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * 一个排班周期。GMSR 的 CD 在每周四刷新，所以一周 = 周四 ~ 下周三。
 */
@Entity
@Table(name = "week_schedule")
public class WeekSchedule {

    /** 周期标识，用该周周四的日期：2026-10-08 */
    @Id
    @Column(name = "week_key", length = 16)
    private String weekKey;

    /** 展示用名称，例如「10/08 ~ 10/14」 */
    @Column(nullable = false, length = 32)
    private String label;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** 备注，例如「本周」「上周」 */
    @Column(length = 64)
    private String note;

    public String getWeekKey() {
        return weekKey;
    }

    public void setWeekKey(String weekKey) {
        this.weekKey = weekKey;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
