package tw.ke.garminmapshare;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

final class UrlExpander {
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

            int code = connection.getResponseCode();
            if (code < 300 || code >= 400) {
                connection.disconnect();
                return text.replace(url, current);
            }

            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.length() == 0) {
                return text.replace(url, current);
            }

            URL base = new URL(current);
            current = new URL(base, location).toString();
        }
        return text.replace(url, current);
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
