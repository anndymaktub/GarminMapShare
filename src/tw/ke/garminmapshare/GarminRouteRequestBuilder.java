package tw.ke.garminmapshare;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

final class GarminRouteRequestBuilder {
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private GarminRouteRequestBuilder() {
    }

    static byte[] build(double lat, double lon, String name) {
        long latSemi = GarminCoordinateConverter.toSemiCircle(lat);
        long lonSemi = GarminCoordinateConverter.toSemiCircle(lon);

        List<String> params = new ArrayList<String>();
        params.add("lat=" + encode(Long.toString(latSemi)));
        params.add("lon=" + encode(Long.toString(lonSemi)));

        if (name != null && name.trim().length() > 0) {
            params.add("name=" + encode(name.trim()));
        }

        String body = join(params, "&");
        int contentLength = body.getBytes(UTF_8).length;
        String request = "POST /new-route HTTP/1.1\r\n"
                + "Host: gps-device\r\n"
                + "Content-Length: " + contentLength + "\r\n"
                + "\r\n"
                + body
                + "\r\n";
        return request.getBytes(UTF_8);
    }

    static String buildPreview(double lat, double lon, String name) {
        return new String(build(lat, lon, name), UTF_8);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(separator);
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }
}
