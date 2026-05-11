package tw.ke.garminmapshare;

final class GarminCoordinateConverter {
    private static final double SEMI_CIRCLE_SCALE = (double) (1L << 31);

    private GarminCoordinateConverter() {
    }

    static long toSemiCircle(double degrees) {
        return (long) (degrees * SEMI_CIRCLE_SCALE / 180.0d);
    }

    static double fromSemiCircle(long semiCircle) {
        return semiCircle * 180.0d / SEMI_CIRCLE_SCALE;
    }
}
