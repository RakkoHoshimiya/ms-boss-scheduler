package com.gmsr.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "boss")
public class Boss extends AuditedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private BossGroup group;

    @Column(nullable = false, length = 64)
    private String name;

    /** 默认难度，车次可单独覆盖。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private BossTier tier = BossTier.NONE;

    /** 队伍人数上限。 */
    @Column(nullable = false)
    private int capacity = 6;

    /** 头像图标文件名（不含扩展名），对应前端 /boss-icons/{iconKey}.png */
    @Column(name = "icon_key", length = 32)
    private String iconKey;

    /**
     * 融合车标记：同 key 的 BOSS 会合成一个车次一起打（要打全打、要不打都不打）。
     * 为空表示不参与融合，正常单独排班。
     */
    @Column(name = "combo_key", length = 32)
    private String comboKey;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * 这个 BOSS 实际存在的难度，逗号分隔（如 "N,H,EX"）。
     * 排班页的难度选项以此为准，避免出现游戏里根本没有的难度。
     */
    @Column(name = "tiers", length = 64)
    private String tiers;

    @Column(length = 255)
    private String note;

    public BossGroup getGroup() {
        return group;
    }

    public void setGroup(BossGroup group) {
        this.group = group;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BossTier getTier() {
        return tier;
    }

    public void setTier(BossTier tier) {
        this.tier = tier;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public String getIconKey() {
        return iconKey;
    }

    public void setIconKey(String iconKey) {
        this.iconKey = iconKey;
    }

    public String getTiers() {
        return tiers;
    }

    public void setTiers(String tiers) {
        this.tiers = tiers;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getComboKey() {
        return comboKey;
    }

    public void setComboKey(String comboKey) {
        this.comboKey = comboKey;
    }
}
