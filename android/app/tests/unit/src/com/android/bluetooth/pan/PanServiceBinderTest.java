/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.bluetooth.pan;

import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PanServiceBinderTest {
    private static final String REMOTE_DEVICE_ADDRESS = "00:00:00:00:00:00";

    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private PanService mService;

    BluetoothDevice mRemoteDevice;

    PanService.BluetoothPanBinder mBinder;

    @Before
    public void setUp() throws Exception {
        mRemoteDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(REMOTE_DEVICE_ADDRESS);
        mBinder = new PanService.BluetoothPanBinder(mService);
    }

    @Test
    public void connect_callsServiceMethod() {
        mBinder.connect(mRemoteDevice, null);

        verify(mService).connect(mRemoteDevice);
    }

    @Test
    public void disconnect_callsServiceMethod() {
        mBinder.disconnect(mRemoteDevice, null);

        verify(mService).disconnect(mRemoteDevice);
    }

    @Test
    public void getConnectedDevices_callsServiceMethod() {
        mBinder.getConnectedDevices(null);

        verify(mService).getConnectedDevices();
    }

    @Test
    public void getDevicesMatchingConnectionStates_callsServiceMethod() {
        int[] states = new int[] {BluetoothProfile.STATE_CONNECTED};
        mBinder.getDevicesMatchingConnectionStates(states, null);

        verify(mService).getDevicesMatchingConnectionStates(states);
    }

    @Test
    public void getConnectionState_callsServiceMethod() {
        mBinder.getConnectionState(mRemoteDevice, null);

        verify(mService).getConnectionState(mRemoteDevice);
    }

    @Test
    public void setConnectionPolicy_callsServiceMethod() {
        int connectionPolicy = BluetoothProfile.CONNECTION_POLICY_ALLOWED;
        mBinder.setConnectionPolicy(mRemoteDevice, connectionPolicy, null);

        verify(mService).setConnectionPolicy(mRemoteDevice, connectionPolicy);
    }

    @Test
    public void isTetheringOn_callsServiceMethod() {
        mBinder.isTetheringOn(null);

        verify(mService).isTetheringOn();
    }

    @Test
    public void cleanUp_doesNotCrash() {
        mBinder.cleanup();
    }
}
