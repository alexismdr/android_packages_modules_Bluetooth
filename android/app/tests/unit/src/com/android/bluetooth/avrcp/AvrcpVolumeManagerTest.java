/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.bluetooth.avrcp;

import static com.android.bluetooth.avrcp.AvrcpVolumeManager.AVRCP_MAX_VOL;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.media.AudioManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;


import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;


@SmallTest
@RunWith(AndroidJUnit4.class)
public class AvrcpVolumeManagerTest {
    private static final String REMOTE_DEVICE_ADDRESS = "00:01:02:03:04:05";
    private static final int TEST_DEVICE_MAX_VOLUME = 25;

    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    AvrcpNativeInterface mNativeInterface;

    @Mock
    AudioManager mAudioManager;

    Context mContext;
    BluetoothDevice mRemoteDevice;
    AvrcpVolumeManager mAvrcpVolumeManager;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        when(mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .thenReturn(TEST_DEVICE_MAX_VOLUME);
        mRemoteDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(REMOTE_DEVICE_ADDRESS);
        mAvrcpVolumeManager = new AvrcpVolumeManager(mContext, mAudioManager, mNativeInterface);
    }

    @After
    public void tearDown() throws Exception {
        mAvrcpVolumeManager.removeStoredVolumeForDevice(mRemoteDevice);
    }

    @Test
    public void avrcpVolumeConversion() {
        assertThat(AvrcpVolumeManager.avrcpToSystemVolume(0)).isEqualTo(0);
        assertThat(AvrcpVolumeManager.avrcpToSystemVolume(AVRCP_MAX_VOL))
                .isEqualTo(TEST_DEVICE_MAX_VOLUME);

        assertThat(AvrcpVolumeManager.systemToAvrcpVolume(0)).isEqualTo(0);
        assertThat(AvrcpVolumeManager.systemToAvrcpVolume(TEST_DEVICE_MAX_VOLUME))
                .isEqualTo(AVRCP_MAX_VOL);
    }

    @Test
    public void dump() {
        StringBuilder sb = new StringBuilder();
        mAvrcpVolumeManager.dump(sb);

        assertThat(sb.toString()).isNotEmpty();
    }

    @Test
    public void sendVolumeChanged() {
        mAvrcpVolumeManager.sendVolumeChanged(mRemoteDevice, TEST_DEVICE_MAX_VOLUME);

        verify(mNativeInterface).sendVolumeChanged(mRemoteDevice, AVRCP_MAX_VOL);
    }

    @Test
    public void setVolume() {
        mAvrcpVolumeManager.setVolume(mRemoteDevice, AVRCP_MAX_VOL);

        verify(mAudioManager)
                .setStreamVolume(
                        eq(AudioManager.STREAM_MUSIC), eq(TEST_DEVICE_MAX_VOLUME), anyInt());
    }

    @Test
    public void switchVolumeDevice() throws InterruptedException {
        mAvrcpVolumeManager.volumeDeviceSwitched(mRemoteDevice);
        mAvrcpVolumeManager.deviceConnected(mRemoteDevice, true);

        // verify whether switchVolumeDevice is called by checking
        // mAudioManager.setDeviceVolumeBehavior().
        // Since it's done in an async thread, we need to add a timeout to await completion
        verify(mAudioManager, timeout(1_000)).setDeviceVolumeBehavior(any(), anyInt());
    }

    @Test
    public void switchVolumeDevice_reverseEventOrder() throws InterruptedException {
        mAvrcpVolumeManager.deviceConnected(mRemoteDevice, true);
        mAvrcpVolumeManager.volumeDeviceSwitched(mRemoteDevice);

        // verify whether switchVolumeDevice is called by checking
        // mAudioManager.setDeviceVolumeBehavior().
        // Since it's done in an async thread, we need to add a timeout to await completion
        verify(mAudioManager, timeout(1_000)).setDeviceVolumeBehavior(any(), anyInt());
    }
}
