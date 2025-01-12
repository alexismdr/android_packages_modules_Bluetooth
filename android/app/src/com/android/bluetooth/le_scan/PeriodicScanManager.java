/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.bluetooth.le_scan;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.IPeriodicAdvertisingCallback;
import android.bluetooth.le.PeriodicAdvertisingReport;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.gatt.GattServiceConfig;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Bluetooth LE Periodic scans
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class PeriodicScanManager {
    private static final String TAG = GattServiceConfig.TAG_PREFIX + "SyncManager";

    private final BluetoothAdapter mAdapter;
    private final PeriodicScanNativeInterface mNativeInterface;
    Map<IBinder, SyncInfo> mSyncs = new ConcurrentHashMap<>();
    Map<IBinder, SyncTransferInfo> mSyncTransfers = Collections.synchronizedMap(new HashMap<>());
    static int sTempRegistrationId = -1;

    /** Constructor of {@link PeriodicScanManager}. */
    public PeriodicScanManager(AdapterService adapterService) {
        Log.d(TAG, "periodic scan manager created");
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mNativeInterface = PeriodicScanNativeInterface.getInstance();
        mNativeInterface.init(this);
    }

    public void cleanup() {
        Log.d(TAG, "cleanup()");
        mNativeInterface.cleanup();
        mSyncs.clear();
        sTempRegistrationId = -1;
    }

    class SyncTransferInfo {
        public String address;
        public SyncDeathRecipient deathRecipient;
        public IPeriodicAdvertisingCallback callback;

        SyncTransferInfo(String address, IPeriodicAdvertisingCallback callback) {
            this.address = address;
            this.callback = callback;
        }
    }

    class SyncInfo {
        /* When id is negative, the registration is ongoing. When the registration finishes, id
         * becomes equal to sync_handle */
        public Integer id;
        public Integer advSid;
        public String address;
        public Integer skip;
        public Integer timeout;
        public SyncDeathRecipient deathRecipient;
        public IPeriodicAdvertisingCallback callback;

        SyncInfo(Integer id, Integer advSid, String address, Integer skip, Integer timeout,
                SyncDeathRecipient deathRecipient,
                IPeriodicAdvertisingCallback callback) {
            this.id = id;
            this.advSid = advSid;
            this.address = address;
            this.skip = skip;
            this.timeout = timeout;
            this.deathRecipient = deathRecipient;
            this.callback = callback;
        }
    }

    Map.Entry<IBinder, SyncTransferInfo> findSyncTransfer(String address) {
        Map.Entry<IBinder, SyncTransferInfo> entry = null;
        for (Map.Entry<IBinder, SyncTransferInfo> e : mSyncTransfers.entrySet()) {
            if (e.getValue().address.equals(address)) {
                entry = e;
                break;
            }
        }
        return entry;
    }

    IBinder toBinder(IPeriodicAdvertisingCallback e) {
        return ((IInterface) e).asBinder();
    }

    class SyncDeathRecipient implements IBinder.DeathRecipient {
        public IPeriodicAdvertisingCallback callback;

        SyncDeathRecipient(IPeriodicAdvertisingCallback callback) {
            this.callback = callback;
        }

        @Override
        public void binderDied() {
            Log.d(TAG, "Binder is dead - unregistering advertising set");
            stopSync(callback);
        }
    }

    Map.Entry<IBinder, SyncInfo> findSync(int syncHandle) {
        Map.Entry<IBinder, SyncInfo> entry = null;
        for (Map.Entry<IBinder, SyncInfo> e : mSyncs.entrySet()) {
            if (e.getValue().id == syncHandle) {
                entry = e;
                break;
            }
        }
        return entry;
    }

    Map.Entry<IBinder, SyncInfo> findMatchingSync(int advSid, String address) {
        Map.Entry<IBinder, SyncInfo> entry = null;
        for (Map.Entry<IBinder, SyncInfo> e : mSyncs.entrySet()) {
            if (e.getValue().advSid == advSid && e.getValue().address.equals(address)) {
                return entry = e;
            }
        }
        return entry;
    }

    Map<IBinder, SyncInfo> findAllSync(int syncHandle) {
        Map<IBinder, SyncInfo> syncMap = new HashMap<IBinder, SyncInfo>();
        for (Map.Entry<IBinder, SyncInfo> e : mSyncs.entrySet()) {
            if (e.getValue().id != syncHandle) {
                continue;
            }
            syncMap.put(e.getKey(), new SyncInfo(e.getValue().id,
                                                 e.getValue().advSid,
                                                 e.getValue().address,
                                                 e.getValue().skip,
                                                 e.getValue().timeout,
                                                 e.getValue().deathRecipient,
                                                 e.getValue().callback));
        }
        return syncMap;
    }

    void onSyncStarted(int regId, int syncHandle, int sid, int addressType, String address, int phy,
            int interval, int status) throws Exception {
        Map<IBinder, SyncInfo> syncMap = findAllSync(regId);
        if (syncMap.size() == 0) {
            Log.d(TAG, "onSyncStarted() - no callback found for regId " + regId);
            mNativeInterface.stopSync(syncHandle);
            return;
        }

        synchronized (mSyncs) {
            for (Map.Entry<IBinder, SyncInfo> e : mSyncs.entrySet()) {
                if (e.getValue().id != regId) {
                    continue;
                }
                IPeriodicAdvertisingCallback callback = e.getValue().callback;
                if (status == 0) {
                    Log.d(TAG, "onSyncStarted: updating id with syncHandle " + syncHandle);
                    e.setValue(new SyncInfo(syncHandle, sid, address, e.getValue().skip,
                                            e.getValue().timeout, e.getValue().deathRecipient,
                                            callback));
                    callback.onSyncEstablished(
                            syncHandle,
                            mAdapter.getRemoteLeDevice(address, addressType),
                            sid,
                            e.getValue().skip,
                            e.getValue().timeout,
                            status);
                } else {
                    callback.onSyncEstablished(
                            syncHandle,
                            mAdapter.getRemoteLeDevice(address, addressType),
                            sid,
                            e.getValue().skip,
                            e.getValue().timeout,
                            status);
                    IBinder binder = e.getKey();
                    binder.unlinkToDeath(e.getValue().deathRecipient, 0);
                    mSyncs.remove(binder);
                }
            }
        }
    }

    void onSyncReport(int syncHandle, int txPower, int rssi, int dataStatus, byte[] data)
            throws Exception {
        Map<IBinder, SyncInfo> syncMap = findAllSync(syncHandle);
        if (syncMap.isEmpty()) {
            Log.i(TAG, "onSyncReport() - no callback found for syncHandle " + syncHandle);
            return;
        }
        for (Map.Entry<IBinder, SyncInfo> e :syncMap.entrySet()) {
            IPeriodicAdvertisingCallback callback = e.getValue().callback;
            PeriodicAdvertisingReport report =
                    new PeriodicAdvertisingReport(syncHandle, txPower, rssi, dataStatus,
                            ScanRecord.parseFromBytes(data));
            callback.onPeriodicAdvertisingReport(report);
        }
    }

    void onSyncLost(int syncHandle) throws Exception {
        Map<IBinder, SyncInfo> syncMap = findAllSync(syncHandle);
        if (syncMap.isEmpty()) {
            Log.i(TAG, "onSyncLost() - no callback found for syncHandle " + syncHandle);
            return;
        }
        for (Map.Entry<IBinder, SyncInfo> e :syncMap.entrySet()) {
            IPeriodicAdvertisingCallback callback = e.getValue().callback;
            IBinder binder = toBinder(callback);
            synchronized (mSyncs) {
                mSyncs.remove(binder);
            }
            callback.onSyncLost(syncHandle);

        }
    }

    void onBigInfoReport(int syncHandle, boolean encrypted) throws Exception {
        Map<IBinder, SyncInfo> syncMap = findAllSync(syncHandle);
        if (syncMap.isEmpty()) {
            Log.i(TAG, "onBigInfoReport() - no callback found for syncHandle " + syncHandle);
            return;
        }
        for (Map.Entry<IBinder, SyncInfo> e : syncMap.entrySet()) {
            IPeriodicAdvertisingCallback callback = e.getValue().callback;
            callback.onBigInfoAdvertisingReport(syncHandle, encrypted);
        }
    }

    public void startSync(ScanResult scanResult, int skip, int timeout,
            IPeriodicAdvertisingCallback callback) {
        SyncDeathRecipient deathRecipient = new SyncDeathRecipient(callback);
        IBinder binder = toBinder(callback);
        try {
            binder.linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            throw new IllegalArgumentException("Can't link to periodic scanner death");
        }

        String address = scanResult.getDevice().getAddress();
        int addressType = scanResult.getDevice().getAddressType();
        int sid = scanResult.getAdvertisingSid();
        Log.d(
                TAG,
                "startSync for Device: "
                        + address
                        + " addressType: "
                        + addressType
                        + " sid: "
                        + sid);
        synchronized (mSyncs) {
            Map.Entry<IBinder, SyncInfo> entry = findMatchingSync(sid, address);
            if (entry != null) {
                //Found matching sync. Copy sync handle
                Log.d(TAG, "startSync: Matching entry found");
                mSyncs.put(binder, new SyncInfo(entry.getValue().id, sid, address,
                        entry.getValue().skip, entry.getValue().timeout, deathRecipient,
                        callback));
                if (entry.getValue().id >= 0) {
                    try {
                        callback.onSyncEstablished(
                                entry.getValue().id,
                                mAdapter.getRemoteLeDevice(address, addressType),
                                sid,
                                entry.getValue().skip,
                                entry.getValue().timeout,
                                0 /*success*/);
                    } catch (RemoteException e) {
                        throw new IllegalArgumentException("Can't invoke callback");
                    }
                } else {
                    Log.d(TAG, "startSync(): sync pending for same remote");
                }
                return;
            }
        }

        int cbId = --sTempRegistrationId;
        mSyncs.put(binder, new SyncInfo(cbId, sid, address, skip, timeout,
                deathRecipient, callback));

        Log.d(TAG, "startSync() - reg_id=" + cbId + ", callback: " + binder);
        mNativeInterface.startSync(sid, address, skip, timeout, cbId);
    }

    public void stopSync(IPeriodicAdvertisingCallback callback) {
        IBinder binder = toBinder(callback);
        Log.d(TAG, "stopSync() " + binder);
        SyncInfo sync = null;
        synchronized (mSyncs) {
            sync = mSyncs.remove(binder);
        }
        if (sync == null) {
            Log.e(TAG, "stopSync() - no client found for callback");
            return;
        }

        Integer syncHandle = sync.id;
        binder.unlinkToDeath(sync.deathRecipient, 0);
        Log.d(TAG, "stopSync: " + syncHandle);

        synchronized (mSyncs) {
            Map.Entry<IBinder, SyncInfo> entry = findSync(syncHandle);
            if (entry != null) {
                Log.d(TAG, "stopSync() - another app synced to same PA, not stopping sync");
                return;
            }
        }
        Log.d(TAG, "calling stopSyncNative: " + syncHandle.intValue());
        if (syncHandle < 0) {
            Log.i(TAG, "cancelSync() - sync not established yet");
            mNativeInterface.cancelSync(sync.advSid, sync.address);
        } else {
            mNativeInterface.stopSync(syncHandle.intValue());
        }
    }

    void onSyncTransferredCallback(int paSource, int status, String bda) {
        Map.Entry<IBinder, SyncTransferInfo> entry = findSyncTransfer(bda);
        if (entry != null) {
            mSyncTransfers.remove(entry);
            IPeriodicAdvertisingCallback callback = entry.getValue().callback;
            try {
                callback.onSyncTransferred(mAdapter.getRemoteDevice(bda), status);
            } catch (RemoteException e) {
                throw new IllegalArgumentException("Can't find callback for sync transfer");
            }
        }
    }

    public void transferSync(BluetoothDevice bda, int serviceData, int syncHandle) {
        Log.d(TAG, "transferSync()");
        Map.Entry<IBinder, SyncInfo> entry = findSync(syncHandle);
        if (entry == null) {
            Log.d(TAG, "transferSync: callback not registered");
            return;
        }
        //check for duplicate transfers
        mSyncTransfers.put(entry.getKey(), new SyncTransferInfo(bda.getAddress(),
                           entry.getValue().callback));
        mNativeInterface.syncTransfer(bda, serviceData, syncHandle);
    }

    public void transferSetInfo(BluetoothDevice bda, int serviceData,
                  int advHandle, IPeriodicAdvertisingCallback callback) {
        SyncDeathRecipient deathRecipient = new SyncDeathRecipient(callback);
        IBinder binder = toBinder(callback);
        Log.d(TAG, "transferSetInfo() " + binder);
        try {
            binder.linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            throw new IllegalArgumentException("Can't link to periodic scanner death");
        }
        mSyncTransfers.put(binder, new SyncTransferInfo(bda.getAddress(), callback));
        mNativeInterface.transferSetInfo(bda, serviceData, advHandle);
    }
}
