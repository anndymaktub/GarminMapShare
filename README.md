# Garmin Map Share

Android prototype for sending a Google Maps destination to a Garmin GPS head unit over Bluetooth Classic.

The app accepts manual coordinates or a shared Google Maps link, converts decimal latitude/longitude to Garmin semi-circle coordinates, and writes a `POST /new-route` request to the Garmin route RFCOMM service.

## Project Layout

- `src/tw/ke/garminmapshare/` - Android Java source.
- `AndroidManifest.xml` - app manifest, Bluetooth permissions, and share/view intent filters.
- `build.ps1` - local debug APK build script.
- `install.ps1` - installs the debug APK through ADB.

## Build

Run from PowerShell inside `G:\GarminBT\GarminMapShare`:

```powershell
.\build.ps1
```

Output:

```text
build\outputs\GarminMapShare-debug.apk
```

## Install

Connect the Android phone with USB debugging enabled:

```powershell
.\install.ps1
```

The current debug package is:

```text
tw.ke.garminmapshare
```

## Main Flow

1. Pair the phone with the Garmin GPS unit in Android Bluetooth settings.
2. Install and open Garmin Map Share.
3. Grant Bluetooth permission when prompted.
4. Share a place from Google Maps to Garmin Map Share, or enter coordinates manually.
5. Select the Garmin device from the paired-device list.
6. Tap the Garmin send button.

Manual test coordinates:

```text
name = Taipei 101
lat = 25.033964
lon = 121.564468
```

## Garmin Bluetooth Request

The app sends route data to UUID:

```text
59845525-f612-4fde-83d9-1c6c914c4272
```

Request format:

```http
POST /new-route HTTP/1.1
Host: gps-device
Content-Length: <body-byte-length>

lat=<semi-circle>&lon=<semi-circle>&name=<urlEncodedName>
```

Important details:

- Coordinates are converted with `semi = (long)(degrees * 2^31 / 180)`.
- Body parameters are URL encoded as UTF-8.
- `Content-Length` is the UTF-8 byte length of the body.
- The Bluetooth client first tries secure RFCOMM, then falls back to insecure RFCOMM.
- After writing and flushing the request, the socket is held open briefly and any response preview is logged.

## Diagnostics

The diagnostics panel can show:

- raw shared text or URL received from Google Maps
- generated Bluetooth request preview
- semi-circle latitude/longitude values
- selected Garmin device name and address
- send timing, socket mode, request/body byte counts, response bytes, and errors

Enable logging before field tests, then copy or share the log after each send attempt.

## Smartphone Link Fallback

If direct Garmin RFCOMM delivery fails, the app can try Garmin Smartphone Link:

- explicit `geo:` `ACTION_VIEW` to `com.garmin.android.apps.phonelink`
- explicit `ACTION_SEND` with Google Maps text
- chooser fallback when the explicit package route is not available

This fallback is useful for checking whether Smartphone Link can still consume the same destination data on the test phone.

## Notes

- Android 12+ requires `BLUETOOTH_CONNECT` at runtime.
- The app only lists already paired devices; pair the Garmin unit before testing.
- Google Maps short links may need network expansion or geocoding before coordinates can be extracted.
- The embedded map preview uses OpenStreetMap inside a WebView for visual confirmation.
