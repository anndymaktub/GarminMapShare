package tw.ke.garminmapshare;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

final class GarminBluetoothClient {
    static final UUID GARMIN_ROUTE_UUID =
            UUID.fromString("59845525-f612-4fde-83d9-1c6c914c4272");
    private static final int POST_WRITE_HOLD_MS = 1200;
    private static final int MAX_RESPONSE_PREVIEW_BYTES = 256;

    static final class SendResult {
        final int requestBytes;
        final int bodyBytes;
        String socketMode;
        long connectMillis;
        long writeMillis;
        int responseBytes;
        String responsePreview = "";

        SendResult(int requestBytes, int bodyBytes) {
            this.requestBytes = requestBytes;
            this.bodyBytes = bodyBytes;
        }
    }

    SendResult sendRoute(BluetoothDevice device, double lat, double lon, String name) throws IOException {
        byte[] request = GarminRouteRequestBuilder.build(lat, lon, name);
        SendResult result = new SendResult(
                request.length,
                GarminRouteRequestBuilder.buildBodyByteLength(lat, lon, name));

        BluetoothSocket socket = null;
        try {
            cancelDiscovery();
            socket = connect(device, true, result);
            OutputStream outputStream = socket.getOutputStream();
            long writeStart = System.currentTimeMillis();
            outputStream.write(request);
            outputStream.flush();
            result.writeMillis = System.currentTimeMillis() - writeStart;
            holdSocketOpen(socket, result);
            return result;
        } catch (IOException secureError) {
            closeQuietly(socket);
            socket = null;
            try {
                cancelDiscovery();
                socket = connect(device, false, result);
                OutputStream outputStream = socket.getOutputStream();
                long writeStart = System.currentTimeMillis();
                outputStream.write(request);
                outputStream.flush();
                result.writeMillis = System.currentTimeMillis() - writeStart;
                holdSocketOpen(socket, result);
                return result;
            } catch (IOException insecureError) {
                throw combineErrors(secureError, insecureError);
            }
        } finally {
            closeQuietly(socket);
        }
    }

    private static BluetoothSocket connect(
            BluetoothDevice device,
            boolean secure,
            SendResult result) throws IOException {
        BluetoothSocket socket = secure
                ? device.createRfcommSocketToServiceRecord(GARMIN_ROUTE_UUID)
                : device.createInsecureRfcommSocketToServiceRecord(GARMIN_ROUTE_UUID);
        long connectStart = System.currentTimeMillis();
        socket.connect();
        result.socketMode = secure ? "secure" : "insecure";
        result.connectMillis = System.currentTimeMillis() - connectStart;
        return socket;
    }

    private static void cancelDiscovery() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return;
        }
        try {
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) {
            // Android 12+ requires BLUETOOTH_SCAN for discovery state. Sending to a
            // bonded device only needs BLUETOOTH_CONNECT, so do not block delivery.
        }
    }

    private static void holdSocketOpen(BluetoothSocket socket, SendResult result) {
        try {
            Thread.sleep(POST_WRITE_HOLD_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            InputStream inputStream = socket.getInputStream();
            int available = inputStream.available();
            result.responseBytes = available;
            if (available <= 0) {
                return;
            }

            int length = Math.min(available, MAX_RESPONSE_PREVIEW_BYTES);
            byte[] response = new byte[length];
            int read = inputStream.read(response);
            if (read > 0) {
                result.responsePreview = previewBytes(response, read);
            }
        } catch (IOException e) {
            result.responseBytes = -1;
            result.responsePreview = "read-error=" + e.getMessage();
        }
    }

    private static IOException combineErrors(IOException secureError, IOException insecureError) {
        IOException combined = new IOException(
                "secure failed: " + secureError.getMessage()
                        + "; insecure failed: " + insecureError.getMessage());
        combined.initCause(insecureError);
        return combined;
    }

    private static String previewBytes(byte[] bytes, int length) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int value = bytes[i] & 0xff;
            if (value >= 32 && value <= 126) {
                builder.append((char) value);
            } else if (value == '\r') {
                builder.append("\\r");
            } else if (value == '\n') {
                builder.append("\\n");
            } else {
                String hex = Integer.toHexString(value).toUpperCase();
                if (hex.length() == 1) {
                    builder.append("\\x0");
                } else {
                    builder.append("\\x");
                }
                builder.append(hex);
            }
        }
        return builder.toString();
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
