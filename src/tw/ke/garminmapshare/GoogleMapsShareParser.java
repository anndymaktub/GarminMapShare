package tw.ke.garminmapshare;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GoogleMapsShareParser {
    private static final Pattern GEO_PATTERN = Pattern.compile(
            "geo:([-+]?\\d+(?:\\.\\d+)?),\\s*([-+]?\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AT_PATTERN = Pattern.compile(
            "@([-+]?\\d+(?:\\.\\d+)?),\\s*([-+]?\\d+(?:\\.\\d+)?)(?:[,/]|$)");
    private static final Pattern QUERY_COORD_PATTERN = Pattern.compile(
            "([-+]?\\d+(?:\\.\\d+)?),\\s*([-+]?\\d+(?:\\.\\d+)?)");
    private static final Pattern BANG_PATTERN = Pattern.compile(
            "!3d([-+]?\\d+(?:\\.\\d+)?)!4d([-+]?\\d+(?:\\.\\d+)?)");

    private GoogleMapsShareParser() {
    }

    static SharedPlace parse(String text) {
        if (text == null) {
            return null;
        }

        String source = text.trim();
        if (source.length() == 0) {
            return null;
        }

        SharedPlace geo = parseByPattern(GEO_PATTERN, source, source);
        if (geo != null) {
            return geo;
        }

        SharedPlace at = parseByPattern(AT_PATTERN, source, source);
        if (at != null) {
            return at;
        }

        SharedPlace bang = parseByPattern(BANG_PATTERN, source, source);
        if (bang != null) {
            return bang;
        }

        SharedPlace query = parseUriQuery(source);
        if (query != null) {
            return query;
        }

        return parseByPattern(QUERY_COORD_PATTERN, source, source);
    }

    static boolean mayNeedRedirect(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.US);
        return lower.contains("maps.app.goo.gl")
                || lower.contains("goo.gl/maps")
                || lower.contains("maps.google.com/?")
                || lower.contains("google.com/maps?") && parse(text) == null;
    }

    static List<String> extractGeocodeQueries(String text) {
        ArrayList<String> queries = new ArrayList<String>();
        if (text == null) {
            return queries;
        }

        String url = firstUrl(text);
        if (url != null) {
            try {
                Uri uri = Uri.parse(url);
                List<String> segments = uri.getPathSegments();
                for (int i = 0; i < segments.size() - 1; i++) {
                    if ("place".equalsIgnoreCase(segments.get(i))) {
                        addCandidate(queries, Uri.decode(segments.get(i + 1)));
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        addCandidate(queries, extractName(text));
        return queries;
    }

    static String extractDisplayName(String text) {
        String name = extractName(text);
        if (name.length() > 0) {
            return name;
        }
        List<String> queries = extractGeocodeQueries(text);
        return queries.isEmpty() ? "" : queries.get(0);
    }

    private static SharedPlace parseUriQuery(String text) {
        String url = firstUrl(text);
        if (url == null) {
            return null;
        }

        try {
            Uri uri = Uri.parse(url);
            String query = uri.getQueryParameter("query");
            SharedPlace place = parseCoordValue(query, text);
            if (place != null) {
                return place;
            }

            String q = uri.getQueryParameter("q");
            place = parseCoordValue(q, text);
            if (place != null) {
                return place;
            }

            String destination = uri.getQueryParameter("destination");
            return parseCoordValue(destination, text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SharedPlace parseCoordValue(String value, String source) {
        if (value == null) {
            return null;
        }
        Matcher matcher = QUERY_COORD_PATTERN.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        return buildPlace(matcher.group(1), matcher.group(2), source);
    }

    private static SharedPlace parseByPattern(Pattern pattern, String text, String source) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return buildPlace(matcher.group(1), matcher.group(2), source);
    }

    private static SharedPlace buildPlace(String latText, String lonText, String source) {
        try {
            double lat = Double.parseDouble(latText);
            double lon = Double.parseDouble(lonText);
            if (lat < -90.0d || lat > 90.0d || lon < -180.0d || lon > 180.0d) {
                return null;
            }
            return new SharedPlace(lat, lon, extractName(source), source);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extractName(String source) {
        String[] lines = source.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) {
                continue;
            }
            String lower = line.toLowerCase(Locale.US);
            if (lower.startsWith("http://")
                    || lower.startsWith("https://")
                    || lower.startsWith("geo:")) {
                continue;
            }
            return line;
        }
        return "";
    }

    private static void addCandidate(List<String> queries, String value) {
        if (value == null) {
            return;
        }
        String cleaned = value
                .replace('+', ' ')
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() == 0) {
            return;
        }

        String lower = cleaned.toLowerCase(Locale.US);
        if (lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("content://")
                || lower.startsWith("geo:")) {
            return;
        }

        for (int i = 0; i < queries.size(); i++) {
            if (queries.get(i).equals(cleaned)) {
                return;
            }
        }
        queries.add(cleaned);
    }

    private static String firstUrl(String text) {
        Matcher matcher = Pattern.compile("https?://\\S+").matcher(text);
        return matcher.find() ? matcher.group() : null;
    }
}
