package dev.frostguard.engine.nav;

import dev.frostguard.api.domain.PointData;

public final class RallyFlagCoordinates {

    private RallyFlagCoordinates() {
    }

    public static PointData pointForFlag(int flagNumber) {
        return switch (flagNumber) {
            case 2 -> new PointData(140, 120);
            case 3 -> new PointData(210, 120);
            case 4 -> new PointData(280, 120);
            case 5 -> new PointData(350, 120);
            case 6 -> new PointData(420, 120);
            case 7 -> new PointData(490, 120);
            case 8 -> new PointData(560, 120);
            default -> new PointData(70, 120);
        };
    }
}
