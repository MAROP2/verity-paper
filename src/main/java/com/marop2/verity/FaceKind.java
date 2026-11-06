package com.marop2.verity;

/** Лица шара из оригинального мода. */
public enum FaceKind {
    HAPPY("◕‿◕"),
    NEUTRAL("◕_◕"),
    HURT("◕︵◕"),
    HAPPY_SLEEP("◔‿◔"),
    SERIOUS_1("◕︿◕"),
    SERIOUS_2("◕︿◕"),
    SERIOUS_3("◕︿◕"),
    CRAZY("◉‿◉"),
    EVIL("◕̿_◕"),
    SMILING_EVIL("◕▿◕"),
    NOFACE(""),
    HAPPY_TALKING("◕‿◕"),
    NEUTRAL_TALKING("◕‿◕"),
    HURT_TALKING("◕︵◕"),
    SERIOUS_TALKING("◕︿◕"),
    CRAZY_TALKING("◉‿◉"),
    EVIL_TALKING("◕̿_◕"),
    SMILING_EVIL_TALKING("◕▿◕");

    private final String text;

    FaceKind(String text) { this.text = text; }
    public String text() { return text; }

    public static FaceKind forKarma(int karma) {
        if (karma <= 4) return SMILING_EVIL;
        if (karma <= 7) return EVIL;
        if (karma <= 10) return NEUTRAL;
        if (karma <= 14) return SERIOUS_1;
        if (karma <= 17) return SERIOUS_2;
        return HAPPY;
    }
}
