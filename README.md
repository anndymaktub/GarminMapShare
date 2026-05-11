# Garmin Map Share

Android app prototype for sharing a Google Maps location to a Garmin GPS head unit over Bluetooth Classic.

## Build

Run from PowerShell:

```powershell
.\build.ps1
```

Output:

```text
build\outputs\GarminMapShare-debug.apk
```

## Install

Connect the S23 with USB debugging enabled:

```powershell
.\install.ps1
```

## First Test

1. Pair the S23 with the Garmin GPS unit in Android Bluetooth settings.
2. Open the app and allow Bluetooth permission.
3. Enter:

```text
name = Taipei 101
lat = 25.033964
lon = 121.564468
```

4. Select the Garmin device.
5. Tap `傳送到 Garmin`.

The app sends:

```http
POST /new-route HTTP/1.1
Host: gps-device
Content-Length: ...

lat=<semi-circle>&lon=<semi-circle>&name=<urlEncodedName>
```
