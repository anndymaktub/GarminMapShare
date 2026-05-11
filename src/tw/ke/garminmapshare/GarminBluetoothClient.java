package tw.ke.garminmapshare;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

final class GarminBluetoothClient {
    static final UUID GARMIN_ROUTE_UUID =
            UUID.fromString("59845525-f612-4fde-83d9-1c6c914c4272");

    void sendRoute(BluetoothDevice device, double lat, double lon, String name) throws IOException {
        byte[] request = GarminRouteRequestBuilder.build(lat, lon, name);

        BluetoothSocket socket = null;
        try {
            socket = device.createRfcommSocketToServiceRecord(GARMIN_ROUTE_UUID);
            socket.connect();
        } catch (IOException firstError) {
            closeQuietly(socket);
            socket = device.createInsecureRfcommSocketToServiceRecord(GARMIN_ROUTE_UUID);
            socket.connect();
        }

        try {
            OutputStream outputStream = socket.getOutputStream();
            outputStream.write(request);
            outputStream.flush();
        } finally {
            closeQuietly(socket);
        }
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
