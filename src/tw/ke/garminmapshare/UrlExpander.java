package tw.ke.garminmapshare;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UrlExpander {
    private static final int MAX_BODY_CHARS = 512000;
    private static final Pattern MAPS_URL_PATTERN = Pattern.compile(
            "https?://(?:www\\.)?google\\.com/maps[^\\s\"'<>\\\\]+");
    private static final Pattern AT_COORD_PATTERN = Pattern.compile(
            "@[-+]?\\d+(?:\\.\\d+)?,\\s*[-+]?\\d+(?:\\.\\d+)?(?:[,/][^\\s\"'<>\\\\]*)?");
    private static final Pattern BANG_COORD_PATTERN = Pattern.compile(
            "!3d[-+]?\\d+(?:\\.\\d+)?!4d[-+]?\\d+(?:\\.\\d+)?");

    private UrlExpander() {
    }

    static String expandFirstUrl(String text) throws IOException {
        String url = firstUrl(text);
        if (url == null) {
            return text;
        }

        String current = url;
        for (int i = 0; i < 8; i++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 GarminMapShare");
            connection.setRequestProperty("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.6");

            int code = connection.getResponseCode();
            if (code < 300 || code >= 400) {
                String body = readBody(connection);
                connection.disconnect();
                return buildExpandedText(text, url, current, body);
            }

            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.length() == 0) {
                return text.replace(url, current);
            }

            URL base = new URL(current);
            current = new URL(base, location).toString();
            String linkedUrl = decodeQueryParameter(current, "link");
            if (linkedUrl.startsWith("http://") || linkedUrl.startsWith("https://")) {
                current = linkedUrl;
            }
            if (looksLikeMapsCoordinateUrl(current)) {
                return text.replace(url, current);
            }
        }
        return text.replace(url, current);
    }

    private static String buildExpandedText(String text, String originalUrl, String currentUrl, String body) {
        StringBuilder builder = new StringBuilder();
        builder.append(text.replace(originalUrl, currentUrl));

        appendIfNew(builder, decodeQueryParameter(currentUrl, "link"));
        appendUsefulFragments(builder, currentUrl);
        appendUsefulFragments(builder, body);

        return builder.toString();
    }

    private static void appendUsefulFragments(StringBuilder builder, String source) {
        if (source == null || source.length() == 0) {
            return;
        }
        String normalized = normalize(source);
        appendMatches(builder, MAPS_URL_PATTERN, normalized);
        appendMatches(builder, AT_COORD_PATTERN, normalized);
        appendMatches(builder, BANG_COORD_PATTERN, normalized);
    }

    private static void appendMatches(StringBuilder builder, Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            appendIfNew(builder, trimFragment(matcher.group()));
        }
    }

    private static String readBody(HttpURLConnection connection) {
        InputStream stream = null;
        try {
            try {
                stream = connection.getInputStream();
            } catch (IOException e) {
                stream = connection.getErrorStream();
            }
            if (stream == null) {
                return "";
            }
            byte[] buffer = new byte[4096];
            StringBuilder builder = new StringBuilder();
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1 && total < MAX_BODY_CHARS) {
                int count = Math.min(read, MAX_BODY_CHARS - total);
                builder.append(new String(buffer, 0, count, "UTF-8"));
                total += count;
            }
            return builder.toString();
        } catch (Exception ignored) {
            return "";
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String decodeQueryParameter(String url, String key) {
        if (url == null) {
            return "";
        }
        String marker = key + "=";
        int start = url.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = url.indexOf('&', start);
        String value = end >= 0 ? url.substring(start, end) : url.substring(start);
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String normalize(String value) {
        return value
                .replace("\\u003d", "=")
                .replace("\\u0026", "&")
                .replace("\\u003a", ":")
                .replace("\\u002f", "/")
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("%3D", "=")
                .replace("%3d", "=")
                .replace("%2F", "/")
                .replace("%2f", "/")
                .replace("%3A", ":")
                .replace("%3a", ":");
    }

    private static void appendIfNew(StringBuilder builder, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() == 0 || builder.indexOf(trimmed) >= 0) {
            return;
        }
        builder.append("\n").append(trimmed);
    }

    private static String trimFragment(String value) {
        while (value.endsWith(".")
                || value.endsWith(",")
                || value.endsWith(")")
                || value.endsWith("]")
                || value.endsWith("\"")
                || value.endsWith("'")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean looksLikeMapsCoordinateUrl(String url) {
        if (url == null) {
            return false;
        }
        String normalized = normalize(url);
        return AT_COORD_PATTERN.matcher(normalized).find()
                || BANG_COORD_PATTERN.matcher(normalized).find()
                || normalized.matches(".*?/maps/place/[-+]?\\d+(?:\\.\\d+)?,[-+]?\\d+(?:\\.\\d+)?(?:[/\\?].*)?");
    }

    private static String firstUrl(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.startsWith("http://") || part.startsWith("https://")) {
                return part;
            }
        }
        return null;
    }
}
