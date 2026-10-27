package com.bluechat.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.*;
import android.bluetooth.*;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.UUID;

public class MainActivity extends Activity {

    private BluetoothAdapter adapter;
    private LinearLayout root, devicesBox, messagesBox;
    private TextView status;
    private EditText messageInput;
    private BluetoothSocket socket;
    private OutputStream output;
    private volatile boolean connected = false;
    private volatile boolean serverStarted = false;

    private final UUID APP_UUID = UUID.fromString(
        "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
    );

    private final Set<String> deviceAddresses = new HashSet<>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE
                );
                if (device != null) addDevice(device);
            }

            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                status.setText("Scan finished. Select a device.");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BluetoothManager manager = (BluetoothManager)
            getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();

        buildScreen();

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }

        if (adapter == null) {
            status.setText("Bluetooth is not supported on this phone.");
            return;
        }

        requestNeededPermissions();
    }

    private void buildScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 28, 24, 20);
        root.setBackgroundColor(Color.rgb(12, 20, 38));

        TextView title = new TextView(this);
        title.setText("BlueChat");
        title.setTextSize(30);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Bluetooth Chat - No Internet");
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 8, 0, 12);
        root.addView(subtitle);

        status = new TextView(this);
        status.setText("Checking Bluetooth permissions...");
        status.setTextColor(Color.WHITE);
        status.setPadding(0, 8, 0, 12);
        root.addView(status);

        Button bluetoothButton = new Button(this);
        bluetoothButton.setText("Turn Bluetooth ON");
        bluetoothButton.setOnClickListener(v -> turnOnBluetooth());
        root.addView(bluetoothButton);

        Button visibleButton = new Button(this);
        visibleButton.setText("Make This Phone Discoverable");
        visibleButton.setOnClickListener(v -> makeDiscoverable());
        root.addView(visibleButton);

        Button scanButton = new Button(this);
        scanButton.setText("Find Nearby Devices");
        scanButton.setOnClickListener(v -> scanDevices());
        root.addView(scanButton);

        TextView deviceTitle = new TextView(this);
        deviceTitle.setText("Nearby / Paired Devices (tap to connect)");
        deviceTitle.setTextColor(Color.WHITE);
        deviceTitle.setPadding(0, 12, 0, 6);
        root.addView(deviceTitle);

        ScrollView deviceScroll = new ScrollView(this);
        devicesBox = new LinearLayout(this);
        devicesBox.setOrientation(LinearLayout.VERTICAL);
        deviceScroll.addView(devicesBox);
        root.addView(deviceScroll,
            new LinearLayout.LayoutParams(-1, 0, 1));

        TextView chatTitle = new TextView(this);
        chatTitle.setText("Chat Messages");
        chatTitle.setTextColor(Color.WHITE);
        chatTitle.setPadding(0, 10, 0, 5);
        root.addView(chatTitle);

        ScrollView chatScroll = new ScrollView(this);
        messagesBox = new LinearLayout(this);
        messagesBox.setOrientation(LinearLayout.VERTICAL);
        chatScroll.addView(messagesBox);
        root.addView(chatScroll,
            new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setOrientation(LinearLayout.HORIZONTAL);

        messageInput = new EditText(this);
        messageInput.setSingleLine(true);
        messageInput.setHint("Type a message");
        messageInput.setTextColor(Color.WHITE);
        messageInput.setHintTextColor(Color.LTGRAY);
        sendRow.addView(messageInput,
            new LinearLayout.LayoutParams(0, -2, 1));

        Button sendButton = new Button(this);
        sendButton.setText("Send");
        sendButton.setOnClickListener(v -> sendMessage());
        sendRow.addView(sendButton);
        root.addView(sendRow);

        setContentView(root);
    }

    private boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        ArrayList<String> needed = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= 31) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE))
                needed.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), 100);
        } else {
            startBluetoothFeatures();
        }
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);

        if (requestCode == 100) {
            boolean granted = true;
            for (int result : results) {
                if (result != PackageManager.PERMISSION_GRANTED) granted = false;
            }

            if (granted) startBluetoothFeatures();
            else status.setText("Allow Bluetooth permissions in Settings.");
        }
    }

    private void startBluetoothFeatures() {
        if (adapter == null) return;
        turnOnBluetooth();
        showPairedDevices();
        startServer();
    }

    private void turnOnBluetooth() {
        try {
            if (Build.VERSION.SDK_INT >= 31 &&
                !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                requestNeededPermissions();
                return;
            }

            if (!adapter.isEnabled()) {
                Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(intent, 101);
            } else {
                status.setText("Bluetooth is ON. Make this phone discoverable.");
                startServer();
                showPairedDevices();
            }
        } catch (SecurityException e) {
            status.setText("Bluetooth permission is required.");
        }
    }

    private void makeDiscoverable() {
        if (Build.VERSION.SDK_INT >= 31 &&
            !hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            requestNeededPermissions();
            return;
        }

        try {
            Intent intent = new Intent(
                BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivityForResult(intent, 102);
        } catch (Exception e) {
            status.setText("Could not enable discoverability.");
        }
    }

    private void scanDevices() {
        if (adapter == null) return;

        if (Build.VERSION.SDK_INT >= 31 &&
            (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) ||
             !hasPermission(Manifest.permission.BLUETOOTH_CONNECT))) {
            requestNeededPermissions();
            return;
        }

        try {
            if (!adapter.isEnabled()) {
                status.setText("Turn Bluetooth ON first.");
                return;
            }

            if (adapter.isDiscovering()) adapter.cancelDiscovery();

            devicesBox.removeAllViews();
            deviceAddresses.clear();
            showPairedDevices();

            status.setText("Searching nearby devices...");
            adapter.startDiscovery();
        } catch (SecurityException e) {
            status.setText("Bluetooth scan permission is required.");
        }
    }

    private void showPairedDevices() {
        try {
            if (adapter == null) return;
            if (Build.VERSION.SDK_INT >= 31 &&
                !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return;

            Set<BluetoothDevice> paired = adapter.getBondedDevices();
            for (BluetoothDevice device : paired) addDevice(device);
        } catch (SecurityException e) {
            status.setText("Bluetooth permission is required.");
        }
    }

    private void addDevice(BluetoothDevice device) {
        try {
            String address = device.getAddress();
            if (address == null || !deviceAddresses.add(address)) return;

            String name = device.getName();
            if (name == null || name.trim().isEmpty()) name = "Unknown device";

            Button deviceButton = new Button(this);
            deviceButton.setText(name + "\n" + address);
            deviceButton.setOnClickListener(v -> connectTo(device));
            devicesBox.addView(deviceButton);
        } catch (SecurityException e) {
            status.setText("Allow Bluetooth permissions.");
        }
    }

    private synchronized void startServer() {
        if (serverStarted || adapter == null) return;
        serverStarted = true;

        new Thread(() -> {
            BluetoothServerSocket server = null;
            try {
                server = adapter.listenUsingRfcommWithServiceRecord(
                    "BlueChat", APP_UUID);

                while (!isFinishing()) {
                    BluetoothSocket incoming = server.accept();
                    if (incoming != null) {
                        attachSocket(incoming);
                    }
                }
            } catch (IOException | SecurityException e) {
                runOnUiThread(() ->
                    status.setText("Waiting for a Bluetooth connection."));
            } finally {
                try {
                    if (server != null) server.close();
                } catch (IOException ignored) {}
                serverStarted = false;
            }
        }).start();
    }

    private void connectTo(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= 31 &&
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            requestNeededPermissions();
            return;
        }

        status.setText("Connecting...");
        new Thread(() -> {
            BluetoothSocket outgoing = null;
            try {
                if (adapter.isDiscovering()) adapter.cancelDiscovery();

                outgoing = device.createRfcommSocketToServiceRecord(APP_UUID);
                outgoing.connect();
                attachSocket(outgoing);
            } catch (IOException | SecurityException e) {
                try {
                    if (outgoing != null) outgoing.close();
                } catch (IOException ignored) {}

                runOnUiThread(() ->
                    status.setText("Connection failed. Make both phones discoverable and try again."));
            }
        }).start();
    }

    private synchronized void attachSocket(BluetoothSocket newSocket) {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}

        socket = newSocket;

        try {
            output = socket.getOutputStream();
            connected = true;

            runOnUiThread(() ->
                status.setText("Connected to " + safeDeviceName(socket.getRemoteDevice())));

            new Thread(() -> readMessages(socket)).start();
        } catch (IOException | SecurityException e) {
            connected = false;
            runOnUiThread(() -> status.setText("Could not open chat connection."));
        }
    }

    private String safeDeviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null ? "device" : name;
        } catch (SecurityException e) {
            return "device";
        }
    }

    private void sendMessage() {
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) return;

        if (!connected || output == null) {
            status.setText("Connect to another device first.");
            return;
        }

        try {
            output.write((message + "\n").getBytes("UTF-8"));
            output.flush();
            addMessage("Me: " + message);
            messageInput.setText("");
        } catch (IOException e) {
            connected = false;
            status.setText("Message failed. Reconnect and try again.");
        }
    }

    private void readMessages(BluetoothSocket activeSocket) {
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(activeSocket.getInputStream(), "UTF-8"));

            String line;
            while (connected && (line = reader.readLine()) != null) {
                final String received = line;
                runOnUiThread(() -> addMessage("Other: " + received));
            }
        } catch (IOException e) {
            runOnUiThread(() -> status.setText("Chat connection ended."));
        } finally {
            connected = false;
        }
    }

    private void addMessage(String message) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(Color.WHITE);
        text.setTextSize(16);
        text.setPadding(8, 8, 8, 8);
        messagesBox.addView(text);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {}

        try {
            if (adapter != null && adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) {}

        connected = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
    }
}
