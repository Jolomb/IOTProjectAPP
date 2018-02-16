/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jolomb.iotprojectapp;

import android.app.Activity;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.SimpleExpandableListAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * For a given BLE device, this Activity provides the user interface to connect, display data,
 * and display GATT services and characteristics supported by the device.  The Activity
 * communicates with {@code BluetoothLeService}, which in turn interacts with the
 * Bluetooth LE API.
 */
public class DeviceControlActivity extends Activity {

    private enum LockState {
        WAITING_FOR_INPUT_BUFFER,
        WAITING_FOR_ON_BOARD_CLICK,
        RESPONSE_READY
    }
    private final static char REMOTE_WAITING_FOR_INPUT_CHAR = 'W';
    private final static char REMOTE_WAITING_FOR_ONBOARD_BUTTON_CHAR = 'P';
    private final static char REMOTE_LOCK_RESPONSE_READY_CHAR = 'R';

    private final static String TAG = DeviceControlActivity.class.getSimpleName();

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";

    private TextView mConnectionState;
    private String mDeviceName;
    private String mDeviceAddress;
    private BluetoothLeService mBluetoothLeService;

    // Specific char of the remote device
    private BluetoothGattCharacteristic mRemoteLockBufferChar;
    private BluetoothGattCharacteristic mRemoteSignedResponseBuffer;

    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
    private boolean mConnected = false;

    private final String LIST_NAME = "NAME";
    private final String LIST_UUID = "UUID";

    private final int CRYPTO_CHALLANGE_LENGTH = 16;
    private byte mChallangeBytes[];

    private LockState mRemoteLockState;
    private TextView mRemoteLocakStateText;

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                updateConnectionState(R.string.disconnected);
                invalidateOptionsMenu();
                clearUI();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Go over the remote GATT services
                iterateServices(mBluetoothLeService.getSupportedGattServices());

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                String char_uuid = intent.getStringExtra(BluetoothLeService.EXTRA_UUID);
                String char_data = intent.getStringExtra(BluetoothLeService.EXTRA_DATA);
                if (char_uuid.equals(SampleGattAttributes.CRYPTO_SIGNER_RESPONSE_STATE)) {
                    // The state of the locking machine should always be 1 byte == 2 chars in HEX
                    if (char_data.length() == 3) {
                        try {
                            char currentLockState = (char) Integer.parseInt(char_data.trim(), 16);
                            switch (currentLockState) {
                                case REMOTE_WAITING_FOR_INPUT_CHAR:
                                    mRemoteLockState = LockState.WAITING_FOR_INPUT_BUFFER;
                                    break;
                                case REMOTE_WAITING_FOR_ONBOARD_BUTTON_CHAR:
                                    mRemoteLockState = LockState.WAITING_FOR_ON_BOARD_CLICK;
                                    break;
                                case REMOTE_LOCK_RESPONSE_READY_CHAR:
                                    mRemoteLockState = LockState.RESPONSE_READY;
                                    break;
                            }
                            updateRemoteLockState(mRemoteLockState);
                        } catch (NumberFormatException ex) {
                            // Well this means the other side is messed up...
                            updateConnectionState(R.string.connected_non_compatible);
                        }
                    }
                }
            }
        }
    };


    private void clearUI() {
        //mGattServicesList.setAdapter((SimpleExpandableListAdapter) null);
        //mDataField.setText(R.string.no_data);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gatt_services_characteristics);

        final Intent intent = getIntent();
        mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        mRemoteLockBufferChar = null;

        // Sets up UI references.
        mConnectionState = findViewById(R.id.connection_state);
        ((TextView) findViewById(R.id.device_address)).setText(mDeviceAddress);
        mRemoteLocakStateText = findViewById(R.id.locking_state);

        mRemoteLockState = LockState.WAITING_FOR_INPUT_BUFFER;
        updateRemoteLockState(mRemoteLockState);

        getActionBar().setTitle(mDeviceName);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        ImageView lockImage = (ImageView)findViewById(R.id.lock_image);

        lockImage.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                Toast.makeText(DeviceControlActivity.this, "Generating the random Crypto Challange", Toast.LENGTH_LONG).show();
                SecureRandom random = new SecureRandom();
                DeviceControlActivity.this.mChallangeBytes = new byte[CRYPTO_CHALLANGE_LENGTH];
                random.nextBytes(mChallangeBytes);
                final String randomString = new String(mChallangeBytes);

                // Write the challange we just created to the remote GATT char
                DeviceControlActivity.this.mBluetoothLeService.writeCharacteristic(
                        DeviceControlActivity.this.mRemoteLockBufferChar,
                        randomString
                );
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gatt_services, menu);
        if (mConnected) {
            menu.findItem(R.id.menu_connect).setVisible(false);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        } else {
            menu.findItem(R.id.menu_connect).setVisible(true);
            menu.findItem(R.id.menu_disconnect).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_connect:
                mBluetoothLeService.connect(mDeviceAddress);
                return true;
            case R.id.menu_disconnect:
                mBluetoothLeService.disconnect();
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateRemoteLockState(final LockState state) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                switch (state) {
                    case WAITING_FOR_INPUT_BUFFER:
                        mRemoteLocakStateText.setText(R.string.waiting_for_user_click);
                        break;
                    case WAITING_FOR_ON_BOARD_CLICK:
                        mRemoteLocakStateText.setText(R.string.waiting_for_onboard_click);
                        break;
                    case RESPONSE_READY:
                        mRemoteLocakStateText.setText(R.string.lock_response_ready);
                        break;
                }
            }
        });
    }

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mConnectionState.setText(resourceId);
                if (resourceId == R.string.connected_non_compatible) {
                    mConnectionState.setBackgroundColor(Color.RED);
                }
            }
        });
    }


    private void iterateServices(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;
        String uuid = null;
        ArrayList<HashMap<String, String>> gattServiceData = new ArrayList<HashMap<String, String>>();
        ArrayList<ArrayList<HashMap<String, String>>> gattCharacteristicData
                = new ArrayList<ArrayList<HashMap<String, String>>>();
        mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

        boolean isCompatServiceFound = false;

        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            HashMap<String, String> currentServiceData = new HashMap<String, String>();
            uuid = gattService.getUuid().toString();

            // Find out if it's a service we recognize!
            String serviceName = null;
            try {
                serviceName = SampleGattAttributes.lookupNoDefault(uuid);
                if (uuid.equals(SampleGattAttributes.CRYPTO_SIGNER_SERVICE)) {
                    isCompatServiceFound = true;
                }
            } catch (RuntimeException ex) {
                continue;
            }
            currentServiceData.put(
                    LIST_NAME, serviceName);
            currentServiceData.put(LIST_UUID, uuid);
            gattServiceData.add(currentServiceData);

            ArrayList<HashMap<String, String>> gattCharacteristicGroupData =
                    new ArrayList<HashMap<String, String>>();
            List<BluetoothGattCharacteristic> gattCharacteristics =
                    gattService.getCharacteristics();
            ArrayList<BluetoothGattCharacteristic> charas =
                    new ArrayList<BluetoothGattCharacteristic>();

            // Loops through available Characteristics.
            String attributeName;
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                // Get the UUID of the current attribute
                uuid = gattCharacteristic.getUuid().toString();
                try {
                    attributeName = SampleGattAttributes.lookupNoDefault(uuid);
                } catch (RuntimeException ex) {
                    // Only handle attributes we recognize
                    continue;
                }

                charas.add(gattCharacteristic);
                HashMap<String, String> currentCharaData = new HashMap<String, String>();
                currentCharaData.put(
                        LIST_NAME, attributeName);
                currentCharaData.put(LIST_UUID, uuid);
                gattCharacteristicGroupData.add(currentCharaData);

                // If it's the response state we want to get notified about it changing
                if (uuid.equals(SampleGattAttributes.CRYPTO_SIGNER_RESPONSE_STATE)) {
                    mBluetoothLeService.readCharacteristic(gattCharacteristic);
                    mBluetoothLeService.setCharacteristicNotification(gattCharacteristic, true);
                } else if (uuid.equals(SampleGattAttributes.CRYPTO_SIGNER_CHALLANGE_INPUT)) {
                    // Hold this char aside for a while
                    mRemoteLockBufferChar = gattCharacteristic;
                } else if (uuid.equals(SampleGattAttributes.CRYPTO_SIGNER_SIGNED_RESPONSE)) {
                    mRemoteSignedResponseBuffer = gattCharacteristic;
                }


            }
            mGattCharacteristics.add(charas);
            gattCharacteristicData.add(gattCharacteristicGroupData);
        }

        // Make sure the GATT server we connected to is compatible with out locking application
        if (isCompatServiceFound) {
            updateConnectionState(R.string.connected_compatible);
        } else {
            updateConnectionState(R.string.connected_non_compatible);
        }

    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }
}
