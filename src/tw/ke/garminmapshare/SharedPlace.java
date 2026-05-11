package tw.ke.garminmapshare;

final class SharedPlace {
    final double lat;
    final double lon;
    final String name;
    final String source;

    SharedPlace(double lat, double lon, String name, String source) {
        this.lat = lat;
        this.lon = lon;
        this.name = name == null ? "" : name;
        this.source = source == null ? "" : source;
    }
}
