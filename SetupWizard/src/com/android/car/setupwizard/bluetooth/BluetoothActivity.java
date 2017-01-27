/*
 * Copyright (C) 2017 Google Inc.
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

package com.android.car.setupwizard.bluetooth;

import static com.android.setupwizardlib.util.ResultCodes.RESULT_SKIP;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import com.android.car.setupwizard.R;
import com.android.car.setupwizard.bluetooth.BluetoothDeviceHierarchy.BluetoothItem;

import com.android.setupwizardlib.GlifRecyclerLayout;
import com.android.setupwizardlib.items.IItem;
import com.android.setupwizardlib.items.Item;
import com.android.setupwizardlib.items.ItemGroup;
import com.android.setupwizardlib.items.RecyclerItemAdapter;
import com.android.setupwizardlib.util.ResultCodes;
import com.android.setupwizardlib.util.WizardManagerHelper;

/**
 * An Activity that presents the option for the user to pair the current device to a nearby
 * bluetooth device. This screen will list the devices in the order that they are discovered
 * as well as an option to not pair at all.
 */
public class BluetoothActivity extends Activity
        implements RecyclerItemAdapter.OnItemSelectedListener {
    private static final String TAG = "BluetoothActivity";

    /**
     * This value is copied from {@code com.google.android.setupwizard.BaseActivity}. Wizard
     * Manager does not actually return an activity result, but if we invoke Wizard Manager without
     * requesting a result, the framework will choose not to issue a call to onActivityResult with
     * RESULT_CANCELED when navigating backward.
     */
    private static final int REQUEST_CODE_NEXT = 10000;

    private static final int BLUETOOTH_SCAN_RETRY_DELAY = 1000;
    private static final int MAX_BLUETOOTH_SCAN_RETRIES = 3;

    private final Handler mHandler = new Handler();
    private int mScanRetryCount;

    private BluetoothScanReceiver mScanReceiver;
    private BluetoothAdapterReceiver mAdapterReceiver;
    private BluetoothAdapter mAdapter;
    private BluetoothDeviceHierarchy mBluetoothDeviceHierarchy;

    private GlifRecyclerLayout mLayout;
    private Item mScanningIndicator;
    private Item mRescanIndicator;

    /**
     * The current {@link BluetoothDevice} that is being paired to.
     */
    private BluetoothDevice mCurrentBondingDevice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAdapter = BluetoothAdapter.getDefaultAdapter();

        if (mAdapter == null) {
            Log.w(TAG, "No bluetooth adapter found on the device. Skipping to next action.");
            nextAction(RESULT_SKIP);
            return;
        }

        setContentView(R.layout.bluetooth_activity);

        mLayout = (GlifRecyclerLayout) findViewById(R.id.setup_wizard_layout);

        RecyclerItemAdapter adapter = (RecyclerItemAdapter) mLayout.getAdapter();
        adapter.setOnItemSelectedListener(this);

        ItemGroup hierarchy = (ItemGroup) adapter.getRootItemHierarchy();
        mBluetoothDeviceHierarchy =
                (BluetoothDeviceHierarchy) hierarchy.findItemById(R.id.bluetooth_device_list);
        mScanningIndicator = (Item) hierarchy.findItemById(R.id.bluetooth_scanning);
        mRescanIndicator = (Item) hierarchy.findItemById(R.id.bluetooth_rescan);

        Item descriptionItem = (Item) hierarchy.findItemById(R.id.bluetooth_description);
        descriptionItem.setTitle(getText(R.string.bluetooth_description));

        // Assume that a search will be started, so display the progress bar to let the user
        // know that something is going on.
        mLayout.setProgressBarShown(true);

        if (mAdapter.isEnabled()) {
            setUpAndStartScan();
        } else {
            mAdapterReceiver = new BluetoothAdapterReceiver();
            maybeRegisterAdapterReceiver();
            mAdapter.enable();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onStart()");
        }

        if (mAdapter == null) {
            Log.w(TAG, "No bluetooth adapter found on the device. Skipping to next action.");
            nextAction(RESULT_SKIP);
            return;
        }

        maybeRegisterAdapterReceiver();
        registerScanReceiver();
    }

    @Override
    protected void onStop() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onStop()");
        }

        stopScanning();

        if (mScanReceiver != null) {
            unregisterReceiver(mScanReceiver);
        }

        if (mAdapterReceiver != null) {
            unregisterReceiver(mAdapterReceiver);
        }

        super.onStop();
    }

    /**
     * Sets up an Intent filter to listen for bluetooth state changes and initiates a scan for
     * nearby bluetooth devices.
     */
    private void setUpAndStartScan() {
        mBluetoothDeviceHierarchy.clearAllDevices();
        registerScanReceiver();
        startScanning();
    }

    /**
     * Registers a receiver to be listen on changes to the {@link BluetoothAdapter}. This method
     * will only register the receiver if {@link #mAdapterReceiver} is not {@code null}.
     */
    private void maybeRegisterAdapterReceiver() {
        if (mAdapterReceiver == null) {
            return;
        }

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mAdapterReceiver, filter);
    }

    /**
     * Registers an Intent filter to listen for the results of a bluetooth discovery scan as well as
     * changes to individual bluetooth devices.
     */
    private void registerScanReceiver() {
        if (mScanReceiver == null) {
            mScanReceiver = new BluetoothScanReceiver();
        }

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        intentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        intentFilter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(mScanReceiver, intentFilter);
    }

    /**
     * Start a scan for nearby bluetooth devices. If the call to
     * {@link BluetoothAdapter#startDiscovery()} fails, then this method will retry the call after
     * an exponential backoff period based on {@link #BLUETOOTH_SCAN_RETRY_DELAY}.
     *
     * <p>If there is already a bluetooth scan in progress when this function is called, then this
     * function will do nothing.
     */
    private void startScanning() {
        if (mAdapter.isDiscovering()) {
            return;
        }

        boolean success = mAdapter.startDiscovery();

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "startDiscovery() success: " + success);
        }

        // If a scan fails, attempt to try again up to MAX_BLUETOOTH_SCAN_RETRIES tries.
        if (success) {
            mScanRetryCount = 0;
        } else if (mScanRetryCount >= MAX_BLUETOOTH_SCAN_RETRIES) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Reached max retries to initiate a bluetooth scan. Moving onto next "
                        + "action");
            }

            nextAction(RESULT_SKIP);
        } else {
            mHandler.postDelayed(this::startScanning,
                    BLUETOOTH_SCAN_RETRY_DELAY * ++mScanRetryCount);
        }
    }

    /**
     * Stops any scan in that is currently in progress for nearby bluetooth devices.
     */
    private void stopScanning() {
        if (mAdapter != null && mAdapter.isDiscovering()) {
            mAdapter.cancelDiscovery();
        }

        mScanRetryCount = 0;
    }

    @Override
    public void onItemSelected(IItem item) {
        if (item instanceof BluetoothItem) {
            pairOrUnpairDevice((BluetoothItem) item);
            return;
        }

        if (!(item instanceof Item)) {
            return;
        }

        switch (((Item) item).getId()) {
            case R.id.bluetooth_dont_connect:
                nextAction(RESULT_SKIP);
                break;

            case R.id.bluetooth_rescan:
                stopScanning();
                startScanning();
                break;

            default:
                Log.w(TAG, "Unknown item clicked: " + item);
        }
    }

    /**
     * Starts a pairing or unpairing session with the given device based on its current bonded
     * state. For example, if the current item is already paired, it is unpaired and vice versa.
     */
    private void pairOrUnpairDevice(BluetoothItem item) {
        // Pairing is unreliable while scanning, so cancel discovery.
        stopScanning();

        BluetoothDevice device = item.getBluetoothDevice();

        boolean success;
        switch (device.getBondState()) {
            case BluetoothDevice.BOND_BONDED:
                mCurrentBondingDevice = null;
                success = device.removeBond();

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "removeBond() to device (" + device + ") successful: " + success);
                }

                // Immediately update the UI so that the user has feedback on their actions.
                item.updateConnectionState(this /* context */,
                        BluetoothItem.CONNECTION_STATE_DISCONNECTING);
                break;

            case BluetoothDevice.BOND_BONDING:
                mCurrentBondingDevice = null;
                success = device.cancelBondProcess();

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "cancelBondProcess() to device (" + device + ") successful: "
                            + success);
                }

                // Immediately update the UI so that the user has feedback on their actions.
                item.updateConnectionState(this /* context */,
                        BluetoothItem.CONNECTION_STATE_CANCELLING);
                break;

            case BluetoothDevice.BOND_NONE:
                mCurrentBondingDevice = device;
                success = device.createBond();

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "createBond() to device (" + device + ") successful: " + success);
                }

                // Immediately update the UI so that the user has feedback on their actions.
                item.updateConnectionState(this /* context */,
                        BluetoothItem.CONNECTION_STATE_CONNECTING);

            default:
                Log.w(TAG, "Encountered unknown bond state: " + device.getBondState());
        }
    }

    private void nextAction(int resultCode) {
        setResult(resultCode);
        Intent nextIntent = WizardManagerHelper.getNextIntent(getIntent(), resultCode);
        startActivityForResult(nextIntent, REQUEST_CODE_NEXT);
    }

    /**
     * A {@link BroadReceiver} that listens for when the bluetooth adapter has been turned on.
     */
    private class BluetoothAdapterReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action != null && action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);

                if (state == BluetoothAdapter.STATE_ON) {
                    setUpAndStartScan();
                }
            }
        }
    }

    /**
     * Handles bluetooth scan responses and other indicators.
     **/
    private class BluetoothScanReceiver extends BroadcastReceiver {
       @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action == null) {
                return;
            }

            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Received device: " + device);
            }

            switch (action) {
                case BluetoothDevice.ACTION_FOUND:
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Bluetooth device found");
                    }

                    mLayout.setProgressBarShown(false);
                    mScanningIndicator.setVisible(false);
                    mRescanIndicator.setVisible(true);
                    mBluetoothDeviceHierarchy.addOrUpdateDevice(context, device);
                    break;

                case BluetoothAdapter.ACTION_DISCOVERY_STARTED:
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Bluetooth discovery started");
                    }

                    mLayout.setProgressBarShown(true);
                    mScanningIndicator.setVisible(true);
                    mRescanIndicator.setVisible(false);
                    mBluetoothDeviceHierarchy.clearAllDevices();
                    break;

                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Bluetooth discovery finished");
                    }
                    break;

                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Bluetooth bond state changed");
                    }

                    mBluetoothDeviceHierarchy.addOrUpdateDevice(context, device);

                    // When a bluetooth device has been paired, then move onto the next action so
                    // the user is not stuck on this screen for too long.
                    if (device.equals(mCurrentBondingDevice)
                            && device.getBondState() == BluetoothDevice.BOND_BONDED) {
                        nextAction(RESULT_OK);
                    }
                    break;

                case BluetoothDevice.ACTION_NAME_CHANGED:
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Bluetooth device name chaged");
                    }
                    mBluetoothDeviceHierarchy.addOrUpdateDevice(context, device);
                    break;

                default:
                    Log.w(TAG, "Unknown action received: " + action);
            }
        }
    }
}
