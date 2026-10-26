package com.bluechat.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private BluetoothAdapter bluetoothAdapter;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 70, 40, 40);
        layout.setBackgroundColor(Color.rgb(12, 20, 38));

        TextView title = new TextView(this);
        title.setText("BlueChat");
        title.setTextSize(32);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);

        TextView subtitle = new TextView(this);
        subtitle.setText("Chat Without Internet");
        subtitle.setTextSize(17);
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 20, 0, 50);

        status = new TextView(this);
        status.setText("Bluetooth not connected");
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 20, 0, 30);

        Button button = new Button(this);
        button.setText("Turn On Bluetooth");

        BluetoothManager manager =
                (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);

        bluetoothAdapter = manager.getAdapter();

        button.setOnClickListener(v -> turnOnBluetooth());

        layout.addView(title);
        layout.addView(subtitle);
        layout.addView(status);
        layout.addView(button);

        setContentView(layout);
    }

    private void turnOnBluetooth() {

        if (bluetoothAdapter == null) {
            status.setText("Bluetooth is not supported");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    100
            );
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBluetooth =
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBluetooth, 101);
        } else {
            status.setText("Bluetooth is ON");

            Toast.makeText(
                    this,
                    "Bluetooth Ready!",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }
  }
