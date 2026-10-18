package com.gmsr.scheduler.domain;

/** BOSS 难度（对外统一显示英文）。E = Easy，EX = Extreme，不要再混用。 */
public enum BossTier {
    /** 简单 / Easy */
    E("Easy"),
    /** 普通 / Normal */
    N("Normal"),
    /** 困难 / Hard */
    H("Hard"),
    /** 混沌 / Chaos */
    C("Chaos"),
    /** 极限 / Extreme */
    EX("Extreme"),
    NONE("");

    private final String label;

    BossTier(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
