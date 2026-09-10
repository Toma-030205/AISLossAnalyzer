package ais.spatial;

public record GridCellId(int zone, long column, long row) {

    public static final int UTM_ZONE_53_NORTH = 53;

    public GridCellId {
        if (zone <= 0) {
            throw new IllegalArgumentException(
                    "UTM zone must be greater than zero");
        }
    }

    @Override
    public String toString() {
        return "UTM" + zone + "N:" + column + ":" + row;
    }
}
