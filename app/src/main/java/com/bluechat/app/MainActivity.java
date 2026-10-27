package com.bluechat.app;

import android.app.*;
import android.os.*;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.bluetooth.*;
import android.graphics.*;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.*;
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
    private DataOutputStream output;
    private volatile boolean connected = false;
    private volatile boolean serverStarted = false;
    private final Object sendLock = new Object();

    private final UUID APP_UUID = UUID.fromString(
        "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
    );

    private final Set<String> deviceAddresses = new HashSet<>();
    private static final int PICK_IMAGE = 103;
    private static final int MAX_IMAGE_SIZE = 5 * 1024 * 1024;

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
            status.setText("Bluetooth not supported.");
            return;
        }

        requestNeededPermissions();
    }

    private void buildScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 16);
        root.setBackgroundColor(Color.rgb(12, 20, 38));

        TextView title = new TextView(this);
        title.setText("BlueChat");
        title.setTextSize(30);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setText("Bluetooth Chat - No Internet");
        status.setTextColor(Color.WHITE);
        status.setPadding(0, 8, 0, 10);
        root.addView(status);

        addButton("Turn Bluetooth ON", v -> turnOnBluetooth());
        addButton("Make This Phone Discoverable", v -> makeDiscoverable());
        addButton("Find Nearby Devices", v -> scanDevices());

        TextView deviceTitle = new TextView(this);
        deviceTitle.setText("Nearby / Paired Devices");
        deviceTitle.setTextColor(Color.WHITE);
        deviceTitle.setPadding(0, 8, 0, 4);
        root.addView(deviceTitle);

        ScrollView deviceScroll = new ScrollView(this);
        devicesBox = new LinearLayout(this);
        devicesBox.setOrientation(LinearLayout.VERTICAL);
        deviceScroll.addView(devicesBox);
        root.addView(deviceScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView chatTitle = new TextView(this);
        chatTitle.setText("Chat Messages");
        chatTitle.setTextColor(Color.WHITE);
        chatTitle.setPadding(0, 8, 0, 4);
        root.addView(chatTitle);

        ScrollView chatScroll = new ScrollView(this);
        messagesBox = new LinearLayout(this);
        messagesBox.setOrientation(LinearLayout.VERTICAL);
        chatScroll.addView(messagesBox);
        root.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 2));

        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setOrientation(LinearLayout.HORIZONTAL);

        messageInput = new EditText(this);
        messageInput.setSingleLine(true);
        messageInput.setHint("Type a message");
        messageInput.setTextColor(Color.WHITE);
        messageInput.setHintTextColor(Color.LTGRAY);
        sendRow.addView(messageInput, new LinearLayout.LayoutParams(0, -2, 1));

        Button sendButton = new Button(this);
        sendButton.setText("Send");
        sendButton.setOnClickListener(v -> sendMessage());
        sendRow.addView(sendButton);
        root.addView(sendRow);

        addButton("Send Photo", v -> choosePhoto());
        addButton("Clear Messages", v -> clearMessages());

        setContentView(root);
    }

    private void addButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        root.addView(button);
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

        if (Build.VERSION.SDK_INT <= 28 && Build.VERSION.SDK_INT >= 23 &&
            !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
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
            else status.setText("Please allow required permissions in Settings.");
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
                startActivityForResult(
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 101);
            } else {
                status.setText("Bluetooth ON. Make phone discoverable.");
                startServer();
                showPairedDevices();
            }
        } catch (SecurityException e) {
            status.setText("Bluetooth permission required.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 101 && resultCode == RESULT_OK) {
            status.setText("Bluetooth ON.");
            startServer();
            showPairedDevices();
        }

        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK &&
            data != null && data.getData() != null) {
            sendPhoto(data.getData());
        }
    }

    private void makeDiscoverable() {
        try {
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivityForResult(intent, 102);
        } catch (Exception e) {
            status.setText("Could not enable discoverability.");
        }
    }

    private void scanDevices() {
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
            status.setText("Allow Bluetooth permissions.");
        }
    }

    private void showPairedDevices() {
        try {
            if (adapter == null) return;

            for (BluetoothDevice device : adapter.getBondedDevices()) {
                addDevice(device);
            }
        } catch (SecurityException e) {
            status.setText("Bluetooth permission required.");
        }
    }

    private void addDevice(BluetoothDevice device) {
        try {
            String address = device.getAddress();
            if (address == null || !deviceAddresses.add(address)) return;

            String name = device.getName();
            if (name == null || name.trim().isEmpty()) name = "Unknown device";

            Button button = new Button(this);
            button.setText(name + "\n" + address);
            button.setOnClickListener(v -> connectTo(device));
            devicesBox.addView(button);
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
                    if (incoming != null) attachSocket(incoming);
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
                    status.setText("Connection failed. Try again."));
            }
        }).start();
    }

    private synchronized void attachSocket(BluetoothSocket newSocket) {
        try {
            if (socket != null && socket != newSocket) socket.close();
        } catch (IOException ignored) {}

        socket = newSocket;

        try {
            output = new DataOutputStream(socket.getOutputStream());
            connected = true;

            runOnUiThread(() ->
                status.setText("Connected to " +
                    safeDeviceName(socket.getRemoteDevice())));

            BluetoothSocket activeSocket = socket;
            new Thread(() -> readMessages(activeSocket)).start();
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
            byte[] bytes = message.getBytes("UTF-8");
            synchronized (sendLock) {
                output.writeByte(1);
                output.writeInt(bytes.length);
                output.write(bytes);
                output.flush();
            }

            addMessage("Me: " + message);
            messageInput.setText("");
        } catch (IOException e) {
            status.setText("Message failed. Reconnect.");
        }
    }

    private void choosePhoto() {
        if (!connected) {
            status.setText("Connect to another device first.");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_IMAGE);
    }

    private void sendPhoto(Uri uri) {
        new Thread(() -> {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                InputStream input = getContentResolver().openInputStream(uri);
                if (input == null) throw new IOException("Cannot open image.");

                byte[] chunk = new byte[8192];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    buffer.write(chunk, 0, count);
                    if (buffer.size() > MAX_IMAGE_SIZE) {
                        input.close();
                        runOnUiThread(() ->
                            status.setText("Photo too large. Maximum size is 5 MB."));
                        return;
                    }
                }
                input.close();

                byte[] photo = buffer.toByteArray();
                synchronized (sendLock) {
                    if (!connected || output == null)
                        throw new IOException("Not connected.");

                    output.writeByte(2);
                    output.writeInt(photo.length);
                    output.write(photo);
                    output.flush();
                }

                runOnUiThread(() -> {
                    addPhoto("Me: Photo", photo);
                    status.setText("Photo sent.");
                });

            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Could not send photo."));
            }
        }).start();
    }

    private void readMessages(BluetoothSocket activeSocket) {
        try {
            DataInputStream input = new DataInputStream(
                activeSocket.getInputStream());

            while (connected && activeSocket.isConnected()) {
                int type = input.readUnsignedByte();
                int length = input.readInt();

                if (length < 0 || length > MAX_IMAGE_SIZE) {
                    throw new IOException("Invalid message size.");
                }

                byte[] data = new byte[length];
                input.readFully(data);

                if (type == 1) {
                    String message = new String(data, "UTF-8");
                    runOnUiThread(() -> addMessage("Other: " + message));
                } else if (type == 2) {
                    runOnUiThread(() -> {
                        addPhoto("Other: Photo", data);
                        savePhoto(data);
                    });
                }
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

    private void addPhoto(String label, byte[] data) {
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextColor(Color.WHITE);
        caption.setPadding(8, 8, 8, 4);
        messagesBox.addView(caption);

        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bitmap == null) {
            addMessage("Could not display photo.");
            return;
        }

        ImageView image = new ImageView(this);
        image.setImageBitmap(bitmap);
        image.setAdjustViewBounds(true);
        image.setMaxHeight(500);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        messagesBox.addView(image);
    }

    private void savePhoto(byte[] data) {
        try {
            String filename = "BlueChat_" + System.currentTimeMillis() + ".jpg";
            android.content.ContentValues values =
                new android.content.ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

            Uri imageUri;

            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/BlueChat");
                imageUri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            } else {
                if (!hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    status.setText("Allow storage permission to save photos.");
                    return;
                }

                File folder = new File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_PICTURES),
                    "BlueChat");

                if (!folder.exists() && !folder.mkdirs()) {
                    status.setText("Could not create Pictures folder.");
                    return;
                }

                File file = new File(folder, filename);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(data);
                fos.close();

                sendBroadcast(new Intent(
                    Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                    Uri.fromFile(file)));
                status.setText("Photo saved in Pictures/BlueChat.");
         
