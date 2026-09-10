package ais.map;

public enum MapFeatureType {
    LAND,
    COASTLINE,
    RIVER;

    public static MapFeatureType fromSencKind(String kind) {
        return switch (kind) {
            case "LNDARE_a" -> LAND;
            case "COALNE", "LNDARE_l", "SLCONS", "BRIDGE_l" ->
                    COASTLINE;
            case "RIVERS_l", "RIVERS_a" -> RIVER;
            default -> null;
        };
    }
}
