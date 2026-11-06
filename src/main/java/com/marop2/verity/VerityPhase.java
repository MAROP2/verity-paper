package com.marop2.verity;

/**
 * Verity's emotional states, escalating over time while a target player is present.
 */
public enum VerityPhase {
    CALM(1),
    NOTICING(2),
    STALKING(3),
    JUMPSCARE(4);

    private final int id;

    VerityPhase(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static VerityPhase byId(int id) {
        for (VerityPhase p : values()) {
            if (p.id == id) return p;
        }
        return null;
    }
}
