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

import android.bluetooth.BluetoothClass.Device;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.support.annotation.DrawableRes;
import android.support.annotation.IntDef;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.android.car.setupwizard.R;

import com.android.setupwizardlib.items.AbstractItemHierarchy;
import com.android.setupwizardlib.items.IItem;
import com.android.setupwizardlib.items.Item;
import com.android.setupwizardlib.items.ItemHierarchy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * An item hierarchy that represents a list of Bluetooth devices.
 */
public class BluetoothDeviceHierarchy extends AbstractItemHierarchy {
    private static final String TAG = "BtDeviceHierarchy";

    /**
     * A set of all discovered bluetooth devices. The key of this map is the device's MAC address.
     */
    private final HashMap<String, BluetoothItem> mItems = new HashMap<>();

    /**
     * A list of all discovered bluetooth devices' MAC addresses. This list is sorted in the order
     * that the devices were discovered in.
     */
    private final List<String> mAddresses = new ArrayList<>();

    public BluetoothDeviceHierarchy(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Clears the current list of all bluetooth devices.
     */
    public void clearAllDevices() {
        mItems.clear();
        mAddresses.clear();
        notifyChanged();
    }

    /**
     * Adds the given {@link BluetoothDevice} to be displayed. If the device has already been
     * added before, its information is updated based on the given {@code BluetoothDevice}.
     */
    public void addOrUpdateDevice(Context context, @Nullable BluetoothDevice device) {
        if (device == null) {
            return;
        }

        String address = device.getAddress();
        BluetoothItem item;

        if (mItems.containsKey(address)) {
            item = mItems.get(address);
        } else {
            // First time encountering this address, so keep track of it.
            mAddresses.add(address);

            int id = View.generateViewId();
            if (id >= 0x00ffffff) {
                // View.generateViewId returns an incrementing number from 1 to 0x00ffffff.
                // Since we are generating view IDs without recycling, it is theoretically possible
                // for the ID space to run out if the user encounters enough bluetooth devices.
                // Just log if this happens.
                Log.e(TAG, "Ran out of IDs to use for bluetooth item IDs");
            }
            item = new BluetoothItem(id);
        }

        item.update(context, device);
        mItems.put(address, item);

        notifyChanged();
    }

    @Override
    public ItemHierarchy findItemById(int id) {
        if (id == getId()) {
            return this;
        }

        // Child items have generated hash code IDs. Don't try to find those.
        return null;
    }

    @Override
    public int getCount() {
        return mItems.size();
    }

    @Override
    public IItem getItemAt(int position) {
        return mItems.get(mAddresses.get(position));
    }

    /**
     * A {@link Item} that is linked to a particular {@link BluetoothDevice} and is responsible
     * for binding this information to a View to be displayed.
     */
    public static class BluetoothItem extends Item {
        private BluetoothDevice mDevice;

        /**
         * Whether or not the icon for this particular BluetoothDevice has been updated to reflect
         * the type of Bluetooth device this is.
         */
        private boolean mIconUpdated;

        public static final int CONNECTION_STATE_DISCONNECTING = 0;
        public static final int CONNECTION_STATE_CONNECTING = 1;
        public static final int CONNECTION_STATE_CANCELLING = 2;

        @IntDef({
            CONNECTION_STATE_DISCONNECTING,
            CONNECTION_STATE_CONNECTING,
            CONNECTION_STATE_CANCELLING })
        public @interface ConnectionState {}

        public BluetoothItem(int id) {
            setId(id);
        }

        /**
         * Immediately updates the connection state of the device that is represented by this
         * {@link BluetoothItem}. This state is not necessarily tied to the bonded state that
         * will be returned by the {@link BluetoothDevice} associated with this item.
         */
        public void updateConnectionState(Context context, @ConnectionState int state) {
            if (mDevice == null) {
                return;
            }

            switch (state) {
                case CONNECTION_STATE_DISCONNECTING:
                    setSummary(context.getString(R.string.bluetooth_device_disconnecting));
                    break;

                case CONNECTION_STATE_CONNECTING:
                    setSummary(context.getString(R.string.bluetooth_device_connecting));
                    break;

                case CONNECTION_STATE_CANCELLING:
                    setSummary(context.getString(R.string.bluetooth_device_cancelling));
                    break;

                default:
                    // Do nothing.
            }
        }

        /**
         * Associate a {@link BluetoothDevice} with this {@link BluetoothItem}.
         */
        public void update(Context context, BluetoothDevice device) {
            mIconUpdated = false;
            mDevice = device;

            String name = mDevice.getName();
            setTitle(TextUtils.isEmpty(name) ? mDevice.getAddress() : name);

            switch (mDevice.getBondState()) {
                case BluetoothDevice.BOND_BONDED:
                    setSummary(context.getString(R.string.bluetooth_device_connected));
                    break;

                case BluetoothDevice.BOND_BONDING:
                    setSummary(context.getString(R.string.bluetooth_device_connecting));
                    break;

                default:
                    setSummary(null);
            }
        }

        /**
         * Returns the {@link BluetoothDevice} set via {@link #update(Context, BluetoothDevice)}.
         */
        public BluetoothDevice getBluetoothDevice() {
            return mDevice;
        }

        @Override
        public void onBindView(View view) {
            if (mIconUpdated && getIcon() != null) {
                super.onBindView(view);
                return;
            }

            Context context = view.getContext();
            TypedArray a = context.obtainStyledAttributes(
                    new int[] { R.attr.suwListItemIconColor });

            try {
                ColorStateList bluetoothIconColor = a.getColorStateList(0);
                Drawable bluetoothIcon = getDeviceIcon(context).mutate();
                bluetoothIcon.setTintList(bluetoothIconColor);
                setIcon(bluetoothIcon);
            } finally {
                a.recycle();
            }

            mIconUpdated = true;

            super.onBindView(view);
        }

        /**
         * Returns an appropriate {@link Drawable} to use as the icon for the bluetooth device
         * associated with this {@link BluetoothItem}.
         */
        private Drawable getDeviceIcon(Context context) {
            if (mDevice == null) {
                return context.getDrawable(R.drawable.ic_bluetooth_item);
            }

            @DrawableRes int deviceIcon;
            switch (mDevice.getBluetoothClass().getDeviceClass()) {
                case Device.AUDIO_VIDEO_HEADPHONES:
                case Device.AUDIO_VIDEO_WEARABLE_HEADSET:
                    deviceIcon = R.drawable.ic_headset;
                    break;

                case Device.COMPUTER_DESKTOP:
                case Device.COMPUTER_LAPTOP:
                    deviceIcon = R.drawable.ic_computer;
                    break;

                case Device.PHONE_SMART:
                    deviceIcon = R.drawable.ic_smartphone;
                    break;

                case Device.WEARABLE_WRIST_WATCH:
                    deviceIcon = R.drawable.ic_watch;
                    break;

                default:
                    deviceIcon = R.drawable.ic_bluetooth_item;
            }

            return context.getDrawable(deviceIcon);
        }
    }
}
