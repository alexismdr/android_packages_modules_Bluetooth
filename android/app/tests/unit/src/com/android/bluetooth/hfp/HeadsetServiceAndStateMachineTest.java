/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.bluetooth.hfp;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.SetFlagsRule;
import android.telecom.PhoneAccount;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.espresso.intent.Intents;
import androidx.test.espresso.intent.matcher.IntentMatchers;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.RemoteDevices;
import com.android.bluetooth.btservice.SilenceDeviceManager;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.Flags;

import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A set of integration test that involves both {@link HeadsetService} and
 * {@link HeadsetStateMachine}
 */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class HeadsetServiceAndStateMachineTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final String TAG = "HeadsetServiceAndStateMachineTest";
    private static final int ASYNC_CALL_TIMEOUT_MILLIS = 500;
    private static final int START_VR_TIMEOUT_MILLIS = 1000;
    private static final int START_VR_TIMEOUT_WAIT_MILLIS = START_VR_TIMEOUT_MILLIS * 3 / 2;
    private static final int STATE_CHANGE_TIMEOUT_MILLIS = 1000;
    private static final int MAX_HEADSET_CONNECTIONS = 5;
    private static final ParcelUuid[] FAKE_HEADSET_UUID = {BluetoothUuid.HFP};
    private static final String TEST_PHONE_NUMBER = "1234567890";
    private static final String TEST_CALLER_ID = "Test Name";

    private Context mTargetContext;
    private HeadsetService mHeadsetService;
    private BluetoothAdapter mAdapter;
    private HashSet<BluetoothDevice> mBondedDevices = new HashSet<>();
    private final BlockingQueue<Intent> mConnectionStateChangedQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Intent> mActiveDeviceChangedQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Intent> mAudioStateChangedQueue = new LinkedBlockingQueue<>();
    private HeadsetIntentReceiver mHeadsetIntentReceiver;
    private int mOriginalVrTimeoutMs = 5000;
    private PowerManager.WakeLock mVoiceRecognitionWakeLock;
    boolean mIsAdapterServiceSet;
    boolean mIsHeadsetServiceStarted;

    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock private HeadsetNativeInterface mNativeInterface;

    private class HeadsetIntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                Assert.fail("Action is null for intent " + intent);
                return;
            }
            switch (action) {
                case BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED:
                    try {
                        mConnectionStateChangedQueue.put(intent);
                    } catch (InterruptedException e) {
                        Assert.fail("Cannot add Intent to the Connection State Changed queue: "
                                + e.getMessage());
                    }
                    break;
                case BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED:
                    try {
                        mActiveDeviceChangedQueue.put(intent);
                    } catch (InterruptedException e) {
                        Assert.fail("Cannot add Intent to the Active Device Changed queue: "
                                + e.getMessage());
                    }
                    break;
                case BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED:
                    try {
                        mAudioStateChangedQueue.put(intent);
                    } catch (InterruptedException e) {
                        Assert.fail("Cannot add Intent to the Audio State Changed queue: "
                                + e.getMessage());
                    }
                    break;
                default:
                    Assert.fail("Unknown action " + action);
            }

        }
    }

    @Spy private HeadsetObjectsFactory mObjectsFactory = HeadsetObjectsFactory.getInstance();
    @Mock private AdapterService mAdapterService;
    @Mock private ActiveDeviceManager mActiveDeviceManager;
    @Mock private SilenceDeviceManager mSilenceDeviceManager;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private HeadsetSystemInterface mSystemInterface;
    @Mock private AudioManager mAudioManager;
    @Mock private HeadsetPhoneState mPhoneState;
    @Mock private RemoteDevices mRemoteDevices;

    @Before
    public void setUp() throws Exception {
        mTargetContext = InstrumentationRegistry.getTargetContext();
        PowerManager powerManager = mTargetContext.getSystemService(PowerManager.class);
        mVoiceRecognitionWakeLock =
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceRecognitionTest");
        TestUtils.setAdapterService(mAdapterService);
        mIsAdapterServiceSet = true;
        doReturn(MAX_HEADSET_CONNECTIONS).when(mAdapterService).getMaxConnectedAudioDevices();
        doReturn(new ParcelUuid[]{BluetoothUuid.HFP}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        HeadsetObjectsFactory.setInstanceForTesting(mObjectsFactory);
        // This line must be called to make sure relevant objects are initialized properly
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        // Mock methods in AdapterService
        doReturn(FAKE_HEADSET_UUID).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        doAnswer(invocation -> mBondedDevices.toArray(new BluetoothDevice[]{})).when(
                mAdapterService).getBondedDevices();
        doReturn(new BluetoothSinkAudioPolicy.Builder().build()).when(mAdapterService)
                .getRequestedAudioPolicyAsSink(any(BluetoothDevice.class));
        doReturn(mActiveDeviceManager).when(mAdapterService).getActiveDeviceManager();
        doReturn(mSilenceDeviceManager).when(mAdapterService).getSilenceDeviceManager();
        doReturn(mRemoteDevices).when(mAdapterService).getRemoteDevices();
        // Mock system interface
        doNothing().when(mSystemInterface).stop();
        doReturn(mPhoneState).when(mSystemInterface).getHeadsetPhoneState();
        doReturn(mAudioManager).when(mSystemInterface).getAudioManager();
        doReturn(true).when(mSystemInterface).activateVoiceRecognition();
        doReturn(true).when(mSystemInterface).deactivateVoiceRecognition();
        doReturn(mVoiceRecognitionWakeLock).when(mSystemInterface).getVoiceRecognitionWakeLock();
        doReturn(true).when(mSystemInterface).isCallIdle();
        // Mock methods in HeadsetNativeInterface
        doReturn(true).when(mNativeInterface).connectHfp(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectHfp(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).connectAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).setActiveDevice(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).sendBsir(any(BluetoothDevice.class), anyBoolean());
        doReturn(true).when(mNativeInterface).startVoiceRecognition(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).stopVoiceRecognition(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface)
                .atResponseCode(any(BluetoothDevice.class), anyInt(), anyInt());
        // Use real state machines here
        doCallRealMethod().when(mObjectsFactory)
                .makeStateMachine(any(), any(), any(), any(), any(), any());
        // Mock methods in HeadsetObjectsFactory
        doReturn(mSystemInterface).when(mObjectsFactory).makeSystemInterface(any());
        doReturn(mNativeInterface).when(mObjectsFactory).getNativeInterface();
        Intents.init();
        // Modify start VR timeout to a smaller value for testing
        mOriginalVrTimeoutMs = HeadsetService.sStartVrTimeoutMs;
        HeadsetService.sStartVrTimeoutMs = START_VR_TIMEOUT_MILLIS;
        mHeadsetService = new HeadsetService(mTargetContext);
        mHeadsetService.start();
        mHeadsetService.setAvailable(true);
        mIsHeadsetServiceStarted = true;
        Assert.assertNotNull(mHeadsetService);
        verify(mObjectsFactory).makeSystemInterface(mHeadsetService);
        verify(mObjectsFactory).getNativeInterface();
        verify(mNativeInterface).init(MAX_HEADSET_CONNECTIONS + 1, true /* inband ringtone */);

        // Set up the Connection State Changed receiver
        IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
        mHeadsetIntentReceiver = new HeadsetIntentReceiver();
        mTargetContext.registerReceiver(mHeadsetIntentReceiver, filter);
        if (Flags.hfpCodecAptxVoice()) {
            verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).atLeast(1))
                    .enableSwb(
                            eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                            eq(
                                    SystemProperties.getBoolean(
                                            "bluetooth.hfp.codec_aptx_voice.enabled", false)),
                            eq(mHeadsetService.getActiveDevice()));
        }
    }

    @After
    public void tearDown() throws Exception {
        if (!mIsAdapterServiceSet) {
            return;
        }
        mConnectionStateChangedQueue.clear();
        mActiveDeviceChangedQueue.clear();

        if (mIsHeadsetServiceStarted) {
            mTargetContext.unregisterReceiver(mHeadsetIntentReceiver);
            mHeadsetService.stop();
            mHeadsetService = HeadsetService.getHeadsetService();
            Assert.assertNull(mHeadsetService);
            // Clear classes that is spied on and has static life time
            clearInvocations(mNativeInterface);
        }
        HeadsetService.sStartVrTimeoutMs = mOriginalVrTimeoutMs;
        Intents.release();
        HeadsetObjectsFactory.setInstanceForTesting(null);
        mBondedDevices.clear();
        TestUtils.clearAdapterService(mAdapterService);
    }

    /** Test to verify that HeadsetService can be successfully started */
    @Test
    public void testGetHeadsetService() {
        Assert.assertEquals(mHeadsetService, HeadsetService.getHeadsetService());
        // Verify default connection and audio states
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                mHeadsetService.getConnectionState(device));
        Assert.assertEquals(BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                mHeadsetService.getAudioState(device));
    }

    /**
     * Test to verify that {@link HeadsetService#connect(BluetoothDevice)} actually result in a
     * call to native interface to create HFP
     */
    @Test
    public void testConnectFromApi() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.HEADSET))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
        mBondedDevices.add(device);
        Assert.assertTrue(mHeadsetService.connect(device));
        verify(mObjectsFactory).makeStateMachine(device,
                mHeadsetService.getStateMachinesThreadLooper(), mHeadsetService, mAdapterService,
                mNativeInterface, mSystemInterface);
        // Wait ASYNC_CALL_TIMEOUT_MILLIS for state to settle, timing is also tested here and
        // 250ms for processing two messages should be way more than enough. Anything that breaks
        // this indicate some breakage in other part of Android OS
        waitAndVerifyConnectionStateIntent(ASYNC_CALL_TIMEOUT_MILLIS, device,
                BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_DISCONNECTED);
        verify(mNativeInterface).connectHfp(device);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING,
                mHeadsetService.getConnectionState(device));
        Assert.assertEquals(Collections.singletonList(device),
                mHeadsetService.getDevicesMatchingConnectionStates(
                        new int[]{BluetoothProfile.STATE_CONNECTING}));
        // Get feedback from native to put device into connected state
        HeadsetStackEvent connectedEvent =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_SLC_CONNECTED, device);
        mHeadsetService.messageFromNative(connectedEvent);
        // Wait ASYNC_CALL_TIMEOUT_MILLIS for state to settle, timing is also tested here and
        // 250ms for processing two messages should be way more than enough. Anything that breaks
        // this indicate some breakage in other part of Android OS
        waitAndVerifyConnectionStateIntent(ASYNC_CALL_TIMEOUT_MILLIS, device,
                BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED,
                mHeadsetService.getConnectionState(device));
        Assert.assertEquals(Collections.singletonList(device),
                mHeadsetService.getDevicesMatchingConnectionStates(
                        new int[]{BluetoothProfile.STATE_CONNECTED}));
    }

    /**
     * Test to verify that {@link BluetoothDevice#ACTION_BOND_STATE_CHANGED} intent with
     * {@link BluetoothDevice#EXTRA_BOND_STATE} as {@link BluetoothDevice#BOND_NONE} will cause a
     * disconnected device to be removed from state machine map
     */
    @Test
    public void testUnbondDevice_disconnectBeforeUnbond() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.HEADSET))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
        mBondedDevices.add(device);
        Assert.assertTrue(mHeadsetService.connect(device));
        verify(mObjectsFactory).makeStateMachine(device,
                mHeadsetService.getStateMachinesThreadLooper(), mHeadsetService, mAdapterService,
                mNativeInterface, mSystemInterface);
        // Wait ASYNC_CALL_TIMEOUT_MILLIS for state to settle, timing is also tested here and
        // 250ms for processing two messages should be way more than enough. Anything that breaks
        // this indicate some breakage in other part of Android OS
        waitAndVerifyConnectionStateIntent(ASYNC_CALL_TIMEOUT_MILLIS, device,
                BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_DISCONNECTED);
        verify(mNativeInterface).connectHfp(device);
        // Get feedback from native layer to go back to disconnected state
        HeadsetStackEvent connectedEvent =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED, device);
        mHeadsetService.messageFromNative(connectedEvent);
        // Wait ASYNC_CALL_TIMEOUT_MILLIS for state to settle, timing is also tested here and
        // 250ms for processing two messages should be way more than enough. Anything that breaks
        // this indicate some breakage in other part of Android OS
        waitAndVerifyConnectionStateIntent(ASYNC_CALL_TIMEOUT_MILLIS, device,
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_CONNECTING);
        // Send unbond intent
        doReturn(BluetoothDevice.BOND_NONE).when(mAdapterService).getBondState(eq(device));
        mHeadsetService.handleBondStateChanged(
                device, BluetoothDevice.BOND_BONDED, BluetoothDevice.BOND_NONE);
        TestUtils.waitForLooperToFinishScheduledTask(Looper.getMainLooper());
        // Check that the state machine is actually destroyed
        ArgumentCaptor<HeadsetStateMachine> stateMachineArgument =
                ArgumentCaptor.forClass(HeadsetStateMachine.class);
        verify(mObjectsFactory, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .destroyStateMachine(stateMachineArgument.capture());
        Assert.assertEquals(device, stateMachineArgument.getValue().getDevice());
    }

    /**
     * Test to verify that if a device can be property disconnected after
     * {@link BluetoothDevice#ACTION_BOND_STATE_CHANGED} intent with
     * {@link BluetoothDevice#EXTRA_BOND_STATE} as {@link BluetoothDevice#BOND_NONE} is received.
     */
    @Test
    public void testUnbondDevice_disconnectAfterUnbond() {
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.HEADSET))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
        mBondedDevices.add(device);
        Assert.assertTrue(mHeadsetService.connect(device));
        verify(mObjectsFactory).makeStateMachine(device,
                mHeadsetService.getStateMachinesThreadLooper(), mHeadsetService, mAdapterService,
                mNativeInterface, mSystemInterface);
        // Wait ASYNC_CALL_TIMEOUT_MILLIS for state to settle, timing is also tested here and
        // 250ms for processing two messages should be way more than enough. Anything that breaks
        // this indicate some breakage in other part of Android OS
        verify(mNativeInterface, after(ASYNC_CALL_TIMEOUT_MILLIS)).connectHfp(device);
        waitAndVerifyConnectionStateIntent(ASYNC_CALL_TIMEOUT_MILLIS, device,
                BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_DISCONNECTED);
        // Get feedback from native layer to go to connected state
        HeadsetStackEvent connectedEvent =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_SLC_CONNECTED, device);
        mHeadsetService.messageFromNative(connectedEvent);
        // Wait ASYNC_CALL_TIMEOUT_MILLIS for state to settle, timing is also tested here and
        // 250ms for processing two messages should be way more than enough. Anything that breaks
        // this indicate some breakage in other part of Android OS
        waitAndVerifyConnectionStateIntent(ASYNC_CALL_TIMEOUT_MILLIS, device,
                BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED,
                mHeadsetService.getConnectionState(device));
        Assert.assertEquals(Collections.singletonList(device),
                mHeadsetService.getConnectedDevices());
        // Send unbond intent
        doReturn(BluetoothDevice.BOND_NONE).when(mAdapterService).getBondState(eq(device));
        Intent unbondIntent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        unbondIntent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
        unbondIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        InstrumentationRegistry.getTargetContext().sendBroadcast(unbondIntent);
        // Check that the state machine is not destroyed
        verify(mObjectsFactory, after(ASYNC_CALL_TIMEOUT_MILLIS).never()).destroyStateMachine(
                any());
        // Now disconnect the device
        HeadsetStackEvent connectingEvent =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED, device);
        mHeadsetService.messageFromNative(connectingEvent);
        waitAndVerifyConnectionStateIntent(ASYNC_CALL_TIMEOUT_MILLIS, device,
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_CONNECTED);
        // Check that the state machine is destroyed after another async call
        ArgumentCaptor<HeadsetStateMachine> stateMachineArgument =
                ArgumentCaptor.forClass(HeadsetStateMachine.class);
        verify(mObjectsFactory, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .destroyStateMachine(stateMachineArgument.capture());
        Assert.assertEquals(device, stateMachineArgument.getValue().getDevice());
    }

    /**
     * Test the functionality of
     * {@link BluetoothHeadset#startScoUsingVirtualVoiceCall()} and
     * {@link BluetoothHeadset#stopScoUsingVirtualVoiceCall()}
     *
     * Normal start and stop
     */
    @Test
    public void testVirtualCall_normalStartStop() throws RemoteException {
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            BluetoothDevice device = TestUtils.getTestDevice(mAdapter, i);
            connectTestDevice(device);
            Assert.assertThat(mHeadsetService.getConnectedDevices(),
                    Matchers.containsInAnyOrder(mBondedDevices.toArray()));
            Assert.assertThat(mHeadsetService.getDevicesMatchingConnectionStates(
                    new int[]{BluetoothProfile.STATE_CONNECTED}),
                    Matchers.containsInAnyOrder(mBondedDevices.toArray()));
        }
        List<BluetoothDevice> connectedDevices = mHeadsetService.getConnectedDevices();
        Assert.assertThat(connectedDevices, Matchers.containsInAnyOrder(mBondedDevices.toArray()));
        Assert.assertFalse(mHeadsetService.isVirtualCallStarted());
        BluetoothDevice activeDevice = connectedDevices.get(MAX_HEADSET_CONNECTIONS / 2);
        Assert.assertTrue(mHeadsetService.setActiveDevice(activeDevice));
        verify(mNativeInterface).setActiveDevice(activeDevice);
        waitAndVerifyActiveDeviceChangedIntent(ASYNC_CALL_TIMEOUT_MILLIS, activeDevice);
        Assert.assertEquals(activeDevice, mHeadsetService.getActiveDevice());
        // Start virtual call
        Assert.assertTrue(mHeadsetService.startScoUsingVirtualVoiceCall());
        Assert.assertTrue(mHeadsetService.isVirtualCallStarted());
        verifyVirtualCallStartSequenceInvocations(connectedDevices);
        // End virtual call
        Assert.assertTrue(mHeadsetService.stopScoUsingVirtualVoiceCall());
        Assert.assertFalse(mHeadsetService.isVirtualCallStarted());
        verifyVirtualCallStopSequenceInvocations(connectedDevices);
    }

    /**
     * Test the functionality of
     * {@link BluetoothHeadset#startScoUsingVirtualVoiceCall()} and
     * {@link BluetoothHeadset#stopScoUsingVirtualVoiceCall()}
     *
     * Virtual call should be preempted by telecom call
     */
    @Test
    public void testVirtualCall_preemptedByTelecomCall() throws RemoteException {
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            BluetoothDevice device = TestUtils.getTestDevice(mAdapter, i);
            connectTestDevice(device);
            Assert.assertThat(mHeadsetService.getConnectedDevices(),
                    Matchers.containsInAnyOrder(mBondedDevices.toArray()));
            Assert.assertThat(mHeadsetService.getDevicesMatchingConnectionStates(
                    new int[]{BluetoothProfile.STATE_CONNECTED}),
                    Matchers.containsInAnyOrder(mBondedDevices.toArray()));
        }
        List<BluetoothDevice> connectedDevices = mHeadsetService.getConnectedDevices();
        Assert.assertThat(connectedDevices, Matchers.containsInAnyOrder(mBondedDevices.toArray()));
        Assert.assertFalse(mHeadsetService.isVirtualCallStarted());
        BluetoothDevice activeDevice = connectedDevices.get(MAX_HEADSET_CONNECTIONS / 2);
        Assert.assertTrue(mHeadsetService.setActiveDevice(activeDevice));
        verify(mNativeInterface).setActiveDevice(activeDevice);
        verify(mAdapterService).handleActiveDeviceChange(BluetoothProfile.HEADSET, activeDevice);
        Assert.assertEquals(activeDevice, mHeadsetService.getActiveDevice());
        // Start virtual call
        Assert.assertTrue(mHeadsetService.startScoUsingVirtualVoiceCall());
        Assert.assertTrue(mHeadsetService.isVirtualCallStarted());
        verifyVirtualCallStartSequenceInvocations(connectedDevices);
        // Virtual call should be preempted by telecom call
        mHeadsetService.phoneStateChanged(0, 0, HeadsetHalConstants.CALL_STATE_INCOMING,
                TEST_PHONE_NUMBER, 128, "", false);
        Assert.assertFalse(mHeadsetService.isVirtualCallStarted());
        verifyVirtualCallStopSequenceInvocations(connectedDevices);
        verifyCallStateToNativeInvocation(
                new HeadsetCallState(0, 0, HeadsetHalConstants.CALL_STATE_INCOMING,
                        TEST_PHONE_NUMBER, 128, ""), connectedDevices);
    }

    /**
     * Test the functionality of
     * {@link BluetoothHeadset#startScoUsingVirtualVoiceCall()} and
     * {@link BluetoothHeadset#stopScoUsingVirtualVoiceCall()}
     *
     * Virtual call should be rejected when there is a telecom call
     */
    @Test
    public void testVirtualCall_rejectedWhenThereIsTelecomCall() throws RemoteException {
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            BluetoothDevice device = TestUtils.getTestDevice(mAdapter, i);
            connectTestDevice(device);
            Assert.assertThat(mHeadsetService.getConnectedDevices(),
                    Matchers.containsInAnyOrder(mBondedDevices.toArray()));
            Assert.assertThat(mHeadsetService.getDevicesMatchingConnectionStates(
                    new int[]{BluetoothProfile.STATE_CONNECTED}),
                    Matchers.containsInAnyOrder(mBondedDevices.toArray()));
        }
        List<BluetoothDevice> connectedDevices = mHeadsetService.getConnectedDevices();
        Assert.assertThat(connectedDevices, Matchers.containsInAnyOrder(mBondedDevices.toArray()));
        Assert.assertFalse(mHeadsetService.isVirtualCallStarted());
        BluetoothDevice activeDevice = connectedDevices.get(MAX_HEADSET_CONNECTIONS / 2);
        Assert.assertTrue(mHeadsetService.setActiveDevice(activeDevice));
        verify(mNativeInterface).setActiveDevice(activeDevice);
        waitAndVerifyActiveDeviceChangedIntent(ASYNC_CALL_TIMEOUT_MILLIS, activeDevice);
        Assert.assertEquals(activeDevice,
                mHeadsetService.getActiveDevice());
        // Reject virtual call setup if call state is not idle
        when(mSystemInterface.isCallIdle()).thenReturn(false);
        Assert.assertFalse(mHeadsetService.startScoUsingVirtualVoiceCall());
        Assert.assertFalse(mHeadsetService.isVirtualCallStarted());
    }

    /**
     * Test the behavior when dialing outgoing call from the headset
     */
    @Test
    public void testDialingOutCall_NormalDialingOut() throws RemoteException {
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            BluetoothDevice device = TestUtils.getTestDevice(mAdapter, i);
            connectTestDevice(device);
            Assert.assertThat(mHeadsetService.getConnectedDevices(),
                    Matchers.containsInAnyOrder(mBondedDevices.toArray()));
            Assert.assertThat(mHeadsetService.getDevicesMatchingConnectionStates(
                    new int[]{BluetoothProfile.STATE_CONNECTED}),
                    Matchers.containsInAnyOrder(mBondedDevices.toArray()));
        }
        List<BluetoothDevice> connectedDevices = mHeadsetService.getConnectedDevices();
        Assert.assertThat(connectedDevices, Matchers.containsInAnyOrder(mBondedDevices.toArray()));
        Assert.assertFalse(mHeadsetService.isVirtualCallStarted());
        BluetoothDevice activeDevice = connectedDevices.get(0);
        Assert.assertTrue(mHeadsetService.setActiveDevice(activeDevice));
        verify(mNativeInterface).setActiveDevice(activeDevice);
        waitAndVerifyActiveDeviceChangedIntent(ASYNC_CALL_TIMEOUT_MILLIS, activeDevice);
        Assert.assertEquals(activeDevice, mHeadsetService.getActiveDevice());
        // Try dialing out from the a non active Headset
        BluetoothDevice dialingOutDevice = connectedDevices.get(1);
        HeadsetStackEvent dialingOutEvent =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_DIAL_CALL, TEST_PHONE_NUMBER,
                        dialingOutDevice);
        Uri dialOutUri = Uri.fromParts(PhoneAccount.SCHEME_TEL, TEST_PHONE_NUMBER, null);
        Instrumentation.ActivityResult result =
                new Instrumentation.ActivityResult(Activity.RESULT_OK, null);
        Intents.intending(IntentMatchers.hasAction(Intent.ACTION_CALL_PRIVILEGED))
                .respondWith(result);
        mHeadsetService.messageFromNative(dialingOutEvent);
        waitAndVerifyActiveDeviceChangedIntent(ASYNC_CALL_TIMEOUT_MILLIS, dialingOutDevice);
        TestUtils.waitForLooperToFinishScheduledTask(
                mHeadsetService.getStateMachinesThreadLooper());
        Assert.assertTrue(mHeadsetService.hasDeviceInitiatedDialingOut());
        // Make sure the correct intent is fired
        Intents.intended(allOf(IntentMatchers.hasAction(Intent.ACTION_CALL_PRIVILEGED),
                IntentMatchers.hasData(dialOutUri)), Intents.times(1));
        // Further dial out attempt from same device will fail
        mHeadsetService.messageFromNative(dialingOutEvent);
        TestUtils.waitForLooperToFinishScheduledTask(
                mHeadsetService.getStateMachinesThreadLooper());
        verify(mNativeInterface).atResponseCode(dialingOutDevice,
                HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        // Further dial out attempt from other device will fail
        HeadsetStackEvent dialingOutEventOtherDevice =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_DIAL_CALL, TEST_PHONE_NUMBER,
                        activeDevice);
        mHeadsetService.messageFromNative(dialingOutEventOtherDevice);
        TestUtils.waitForLooperToFinishScheduledTask(
                mHeadsetService.getStateMachinesThreadLooper());
        verify(mNativeInterface).atResponseCode(activeDevice, HeadsetHalConstants.AT_RESPONSE_ERROR,
                0);
        TestUtils.waitForNoIntent(ASYNC_CALL_TIMEOUT_MILLIS, mActiveDeviceChangedQueue);
        Assert.assertEquals(dialingOutDevice, mHeadsetService.getActiveDevice());
        // Make sure only one intent is fired
        Intents.intended(allOf(IntentMatchers.hasAction(Intent.ACTION_CALL_PRIVILEGED),
                IntentMatchers.hasData(dialOutUri)), Intents.times(1));
        // Verify that phone state update confirms the dial out event
        mHeadsetService.phoneStateChanged(0, 0, HeadsetHalConstants.CALL_STATE_DIALING,
                TEST_PHONE_NUMBER, 128, "", false);
        HeadsetCallState dialingCallState =
                new HeadsetCallState(0, 0, HeadsetHalConstants.CALL_STATE_DIALING,
                        TEST_PHONE_NUMBER, 128, "");
        verifyCallStateToNativeInvocation(dialingCallState, connectedDevices);
        verify(mNativeInterface).atResponseCode(dialingOutDevice,
                HeadsetHalConstants.AT_RESPONSE_OK, 0);
        // Verify that IDLE phone state clears the dialing out flag
        mHeadsetService.phoneStateChanged(1, 0, HeadsetHalConstants.CALL_STATE_IDLE,
                TEST_PHONE_NUMBER, 128, "", false);
        HeadsetCallState activeCallState =
                new HeadsetCallState(0, 0, HeadsetHalConstants.CALL_STATE_DIALING,
                        TEST_PHONE_NUMBER, 128, "");
        verifyCallStateToNativeInvocation(activeCallState, connectedDevices);
        Assert.assertFalse(mHeadsetService.hasDeviceInitiatedDialingOut());
    }

    /**
     * Test the behavior when dialing outgoing call from the headset
     */
    @Test
    public void testDialingOutCall_DialingOutPreemptVirtualCall() throws RemoteException {
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            BluetoothDevice device = TestUtils.getTestDevice(mAdapter, i);
            connectTestDevice(device);
            Assert.assertThat(mHeadsetService.getConnectedDevices(),
                    Matchers.containsInAnyOrder(mBondedDevices.toArray()));
            Assert.assertThat(mHeadsetService.getDevicesMatchingConnectionStates(
                    new int[]{BluetoothProfile.STATE_CONNECTED}),
                    Matchers.containsInAnyOrder(mBondedDevices.toArray()));
        }
        List<BluetoothDevice> connectedDevices = mHeadsetService.getConnectedDevices();
        Assert.assertThat(connectedDevices, Matchers.containsInAnyOrder(mBondedDevices.toArray()));
        Assert.assertFalse(mHeadsetService.isVirtualCallStarted());
        BluetoothDevice activeDevice = connectedDevices.get(0);
        Assert.assertTrue(mHeadsetService.setActiveDevice(activeDevice));
        verify(mNativeInterface).setActiveDevice(activeDevice);
        waitAndVerifyActiveDeviceChangedIntent(ASYNC_CALL_TIMEOUT_MILLIS, activeDevice);
        Assert.assertEquals(activeDevice, mHeadsetService.getActiveDevice());
        // Start virtual call
        Assert.assertTrue(mHeadsetService.startScoUsingVirtualVoiceCall());
        Assert.assertTrue(mHeadsetService.isVirtualCallStarted());
        verifyVirtualCallStartSequenceInvocations(connectedDevices);
        // Try dialing out from the a non active Headset
        BluetoothDevice dialingOutDevice = connectedDevices.get(1);
        HeadsetStackEvent dialingOutEvent =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_DIAL_CALL, TEST_PHONE_NUMBER,
                        dialingOutDevice);
        Uri dialOutUri = Uri.fromParts(PhoneAccount.SCHEME_TEL, TEST_PHONE_NUMBER, null);
        Instrumentation.ActivityResult result =
                new Instrumentation.ActivityResult(Activity.RESULT_OK, null);
        Intents.intending(IntentMatchers.hasAction(Intent.ACTION_CALL_PRIVILEGED))
                .respondWith(result);
        mHeadsetService.messageFromNative(dialingOutEvent);
        waitAndVerifyActiveDeviceChangedIntent(ASYNC_CALL_TIMEOUT_MILLIS, dialingOutDevice);
        TestUtils.waitForLooperToFinishScheduledTask(
                mHeadsetService.getStateMachinesThreadLooper());
        Assert.assertTrue(mHeadsetService.hasDeviceInitiatedDialingOut());
        // Make sure the correct intent is fired
        Intents.intended(allOf(IntentMatchers.hasAction(Intent.ACTION_CALL_PRIVILEGED),
                IntentMatchers.hasData(dialOutUri)), Intents.times(1));
        // Virtual call should be preempted by dialing out call
        Assert.assertFalse(mHeadsetService.isVirtualCallStarted());
        verifyVirtualCallStopSequenceInvocations(connectedDevices);
    }

    /**
     * Test to verify the following behavior regarding active HF initiated voice recognition
     * in the successful scenario
     *   1. HF device sends AT+BVRA=1
     *   2. HeadsetStateMachine sends out {@link Intent#ACTION_VOICE_COMMAND}
     *   3. AG call {@link BluetoothHeadset#stopVoiceRecognition(BluetoothDevice)} to indicate
     *      that voice recognition has stopped
     *   4. AG sends OK to HF
     *
     * Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_SingleHfInitiatedSuccess() {
        // Connect HF
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).sendBsir(eq(device), eq(true));
        // Start voice recognition
        startVoiceRecognitionFromHf(device);
    }

    /**
     * Same process as {@link
     * HeadsetServiceAndStateMachineTest#testVoiceRecognition_SingleHfInitiatedSuccess()} except the
     * SCO connection is handled by the Audio Framework
     */
    @Test
    public void testVoiceRecognition_SingleHfInitiatedSuccess_ScoManagedByAudio() {
        mSetFlagsRule.enableFlags(Flags.FLAG_IS_SCO_MANAGED_BY_AUDIO);
        Utils.setIsScoManagedByAudioEnabled(true);

        // Connect HF
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).sendBsir(eq(device), eq(true));
        // Start voice recognition
        startVoiceRecognitionFromHf_ScoManagedByAudio(device);

        Utils.setIsScoManagedByAudioEnabled(false);
    }

    /**
     * Test to verify the following behavior regarding active HF stop voice recognition
     * in the successful scenario
     *   1. HF device sends AT+BVRA=0
     *   2. Let voice recognition app to stop
     *   3. AG respond with OK
     *   4. Disconnect audio
     *
     * Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_SingleHfStopSuccess() {
        // Connect HF
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).sendBsir(eq(device), eq(true));
        // Start voice recognition
        startVoiceRecognitionFromHf(device);
        // Stop voice recognition
        HeadsetStackEvent stopVrEvent =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STOPPED, device);
        mHeadsetService.messageFromNative(stopVrEvent);
        verify(mSystemInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).deactivateVoiceRecognition();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2)).atResponseCode(device,
                HeadsetHalConstants.AT_RESPONSE_OK, 0);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).disconnectAudio(device);
        if (Flags.hfpCodecAptxVoice()) {
            verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).atLeast(1))
                    .enableSwb(
                            eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                            anyBoolean(),
                            eq(device));
        }
        verifyNoMoreInteractions(mNativeInterface);
    }

    /**
     * Test to verify the following behavior regarding active HF initiated voice recognition
     * in the failed to activate scenario
     *   1. HF device sends AT+BVRA=1
     *   2. HeadsetStateMachine sends out {@link Intent#ACTION_VOICE_COMMAND}
     *   3. Failed to activate voice recognition through intent
     *   4. AG sends ERROR to HF
     *
     * Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_SingleHfInitiatedFailedToActivate() {
        doReturn(false).when(mSystemInterface).activateVoiceRecognition();
        // Connect HF
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).sendBsir(eq(device), eq(true));
        // Start voice recognition
        HeadsetStackEvent startVrEvent =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED, device);
        mHeadsetService.messageFromNative(startVrEvent);
        verify(mSystemInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).activateVoiceRecognition();
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).atResponseCode(device,
                HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        verifyNoMoreInteractions(mNativeInterface);
        verifyZeroInteractions(mAudioManager);
    }


    /**
     * Test to verify the following behavior regarding active HF initiated voice recognition
     * in the timeout scenario
     *   1. HF device sends AT+BVRA=1
     *   2. HeadsetStateMachine sends out {@link Intent#ACTION_VOICE_COMMAND}
     *   3. AG failed to get back to us on time
     *   4. AG sends ERROR to HF
     *
     * Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_SingleHfInitiatedTimeout() {
        // Connect HF
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).sendBsir(eq(device), eq(true));
        // Start voice recognition
        HeadsetStackEvent startVrEvent =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED, device);
        mHeadsetService.messageFromNative(startVrEvent);
        verify(mSystemInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).activateVoiceRecognition();
        verify(mNativeInterface, timeout(START_VR_TIMEOUT_WAIT_MILLIS))
                .atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        if (Flags.hfpCodecAptxVoice()) {
            verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).atLeast(1))
                    .enableSwb(
                            eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                            anyBoolean(),
                            eq(device));
        }
        verifyNoMoreInteractions(mNativeInterface);
        verifyZeroInteractions(mAudioManager);
    }

    /**
     * Test to verify the following behavior regarding AG initiated voice recognition
     * in the successful scenario
     *   1. AG starts voice recognition and notify the Bluetooth stack via
     *      {@link BluetoothHeadset#startVoiceRecognition(BluetoothDevice)} to indicate that voice
     *      recognition has started
     *   2. AG send +BVRA:1 to HF
     *   3. AG start SCO connection if SCO has not been started
     *
     * Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_SingleAgInitiatedSuccess() {
        // Connect HF
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).sendBsir(eq(device), eq(true));
        // Start voice recognition
        startVoiceRecognitionFromAg();
    }

    /**
     * Same process as {@link
     * HeadsetServiceAndStateMachineTest#testVoiceRecognition_SingleAgInitiatedSuccess()} except the
     * SCO connection is handled by the Audio Framework
     */
    @Test
    public void testVoiceRecognition_SingleAgInitiatedSuccess_ScoManagedByAudio() {
        mSetFlagsRule.enableFlags(Flags.FLAG_IS_SCO_MANAGED_BY_AUDIO);
        Utils.setIsScoManagedByAudioEnabled(true);

        // Connect HF
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).sendBsir(eq(device), eq(true));
        // Start voice recognition
        startVoiceRecognitionFromAg_ScoManagedByAudio();

        Utils.setIsScoManagedByAudioEnabled(false);
    }

    /**
     * Test to verify the following behavior regarding AG initiated voice recognition
     * in the successful scenario
     *   1. AG starts voice recognition and notify the Bluetooth stack via
     *      {@link BluetoothHeadset#startVoiceRecognition(BluetoothDevice)} to indicate that voice
     *      recognition has started, BluetoothDevice is null in this case
     *   2. AG send +BVRA:1 to current active HF
     *   3. AG start SCO connection if SCO has not been started
     *
     * Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_SingleAgInitiatedSuccessNullInput() {
        // Connect HF
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        // Start voice recognition on null argument should go to active device
        Assert.assertTrue(mHeadsetService.startVoiceRecognition(null));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).startVoiceRecognition(device);
    }

    /**
     * Test to verify the following behavior regarding AG initiated voice recognition
     * in the successful scenario
     *   1. AG starts voice recognition and notify the Bluetooth stack via
     *      {@link BluetoothHeadset#startVoiceRecognition(BluetoothDevice)} to indicate that voice
     *      recognition has started, BluetoothDevice is null and active device is null
     *   2. The call should fail
     *
     * Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_SingleAgInitiatedFailNullActiveDevice() {
        // Connect HF
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(null));
        // TODO(b/79760385): setActiveDevice(null) does not propagate to native layer
        // verify(mNativeInterface).setActiveDevice(null);
        Assert.assertNull(mHeadsetService.getActiveDevice());
        // Start voice recognition on null argument should fail
        Assert.assertFalse(mHeadsetService.startVoiceRecognition(null));
    }

    /**
     * Test to verify the following behavior regarding AG stops voice recognition
     * in the successful scenario
     *   1. AG stops voice recognition and notify the Bluetooth stack via
     *      {@link BluetoothHeadset#stopVoiceRecognition(BluetoothDevice)} to indicate that voice
     *      recognition has stopped
     *   2. AG send +BVRA:0 to HF
     *   3. AG stop SCO connection
     *
     * Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_SingleAgStopSuccess() {
        // Connect HF
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).sendBsir(eq(device), eq(true));
        // Start voice recognition
        startVoiceRecognitionFromAg();
        // Stop voice recognition
        Assert.assertTrue(mHeadsetService.stopVoiceRecognition(device));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).stopVoiceRecognition(device);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).disconnectAudio(device);
        if (Flags.hfpCodecAptxVoice()) {
            verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).atLeast(1))
                    .enableSwb(
                            eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                            anyBoolean(),
                            eq(device));
        }
        verifyNoMoreInteractions(mNativeInterface);
    }

    /**
     * Test to verify the following behavior regarding AG initiated voice recognition
     * in the device not connected failure scenario
     *   1. AG starts voice recognition and notify the Bluetooth stack via
     *      {@link BluetoothHeadset#startVoiceRecognition(BluetoothDevice)} to indicate that voice
     *      recognition has started
     *   2. Device is not connected, return false
     *
     * Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_SingleAgInitiatedDeviceNotConnected() {
        // Start voice recognition
        BluetoothDevice disconnectedDevice = TestUtils.getTestDevice(mAdapter, 0);
        Assert.assertFalse(mHeadsetService.startVoiceRecognition(disconnectedDevice));
        verifyNoMoreInteractions(mNativeInterface);
        verifyZeroInteractions(mAudioManager);
    }

    /**
     * Test to verify the following behavior regarding non active HF initiated voice recognition
     * in the successful scenario
     *   1. HF device sends AT+BVRA=1
     *   2. HeadsetStateMachine sends out {@link Intent#ACTION_VOICE_COMMAND}
     *   3. AG call {@link BluetoothHeadset#startVoiceRecognition(BluetoothDevice)} to indicate
     *      that voice recognition has started
     *   4. AG sends OK to HF
     *   5. Suspend A2DP
     *   6. Start SCO if SCO hasn't been started
     *
     * Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_MultiHfInitiatedSwitchActiveDeviceSuccess() {
        // Connect two devices
        BluetoothDevice deviceA = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(deviceA);
        BluetoothDevice deviceB = TestUtils.getTestDevice(mAdapter, 1);
        connectTestDevice(deviceB);
        InOrder inOrder = inOrder(mNativeInterface);
        inOrder.verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBsir(eq(deviceA), eq(true));
        inOrder.verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBsir(eq(deviceB), eq(false));
        inOrder.verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBsir(eq(deviceA), eq(false));
        // Set active device to device B
        Assert.assertTrue(mHeadsetService.setActiveDevice(deviceB));
        verify(mNativeInterface).setActiveDevice(deviceB);
        Assert.assertEquals(deviceB, mHeadsetService.getActiveDevice());
        // Start voice recognition from non active device A
        HeadsetStackEvent startVrEventA =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED, deviceA);
        mHeadsetService.messageFromNative(startVrEventA);
        verify(mSystemInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).activateVoiceRecognition();
        // Active device should have been swapped to device A
        verify(mNativeInterface).setActiveDevice(deviceA);
        Assert.assertEquals(deviceA, mHeadsetService.getActiveDevice());
        // Start voice recognition from other device should fail
        HeadsetStackEvent startVrEventB =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED, deviceB);
        mHeadsetService.messageFromNative(startVrEventB);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .atResponseCode(deviceB, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        // Reply to continue voice recognition
        mHeadsetService.startVoiceRecognition(deviceA);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).atResponseCode(deviceA,
                HeadsetHalConstants.AT_RESPONSE_OK, 0);
        verify(mAudioManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).setA2dpSuspended(true);
        verify(mAudioManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).setLeAudioSuspended(true);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).connectAudio(deviceA);
        if (Flags.hfpCodecAptxVoice()) {
            verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).atLeast(1))
                    .enableSwb(
                            eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                            anyBoolean(),
                            eq(deviceA));
        }
        verifyNoMoreInteractions(mNativeInterface);
    }

    /**
     * Test to verify the following behavior regarding non active HF initiated voice recognition
     * in the successful scenario
     *   1. HF device sends AT+BVRA=1
     *   2. HeadsetStateMachine sends out {@link Intent#ACTION_VOICE_COMMAND}
     *   3. AG call {@link BluetoothHeadset#startVoiceRecognition(BluetoothDevice)} to indicate
     *      that voice recognition has started, but on a wrong HF
     *   4. Headset service instead keep using the initiating HF
     *   5. AG sends OK to HF
     *   6. Suspend A2DP
     *   7. Start SCO if SCO hasn't been started
     *
     * Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_MultiHfInitiatedSwitchActiveDeviceReplyWrongHfSuccess() {
        // Connect two devices
        InOrder inOrder = inOrder(mNativeInterface);
        BluetoothDevice deviceA = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(deviceA);
        inOrder.verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBsir(eq(deviceA), eq(true));
        BluetoothDevice deviceB = TestUtils.getTestDevice(mAdapter, 1);
        connectTestDevice(deviceB);
        inOrder.verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBsir(eq(deviceB), eq(false));
        inOrder.verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBsir(eq(deviceA), eq(false));
        // Set active device to device B
        Assert.assertTrue(mHeadsetService.setActiveDevice(deviceB));
        verify(mNativeInterface).setActiveDevice(deviceB);
        Assert.assertEquals(deviceB, mHeadsetService.getActiveDevice());
        // Start voice recognition from non active device A
        HeadsetStackEvent startVrEventA =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED, deviceA);
        mHeadsetService.messageFromNative(startVrEventA);
        verify(mSystemInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).activateVoiceRecognition();
        // Active device should have been swapped to device A
        verify(mNativeInterface).setActiveDevice(deviceA);
        Assert.assertEquals(deviceA, mHeadsetService.getActiveDevice());
        // Start voice recognition from other device should fail
        HeadsetStackEvent startVrEventB =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED,
                        deviceB);
        mHeadsetService.messageFromNative(startVrEventB);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .atResponseCode(deviceB, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        // Reply to continue voice recognition on a wrong device
        mHeadsetService.startVoiceRecognition(deviceB);
        // We still continue on the initiating HF
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).atResponseCode(deviceA,
                HeadsetHalConstants.AT_RESPONSE_OK, 0);
        verify(mAudioManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).setA2dpSuspended(true);
        verify(mAudioManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).setLeAudioSuspended(true);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).connectAudio(deviceA);
        if (Flags.hfpCodecAptxVoice()) {
            verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).atLeast(1))
                    .enableSwb(
                            eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                            anyBoolean(),
                            eq(deviceA));
        }
        verifyNoMoreInteractions(mNativeInterface);
    }


    /**
     * Test to verify the following behavior regarding AG initiated voice recognition
     * in the successful scenario
     *   1. AG starts voice recognition and notify the Bluetooth stack via
     *      {@link BluetoothHeadset#startVoiceRecognition(BluetoothDevice)} to indicate that voice
     *      recognition has started
     *   2. Suspend A2DP
     *   3. AG send +BVRA:1 to HF
     *   4. AG start SCO connection if SCO has not been started
     *
     * Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_MultiAgInitiatedSuccess() {
        // Connect two devices
        BluetoothDevice deviceA = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(deviceA);
        BluetoothDevice deviceB = TestUtils.getTestDevice(mAdapter, 1);
        connectTestDevice(deviceB);
        InOrder inOrder = inOrder(mNativeInterface);
        inOrder.verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBsir(eq(deviceA), eq(true));
        inOrder.verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBsir(eq(deviceB), eq(false));
        inOrder.verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBsir(eq(deviceA), eq(false));
        // Set active device to device B
        Assert.assertTrue(mHeadsetService.setActiveDevice(deviceB));
        verify(mNativeInterface).setActiveDevice(deviceB);
        Assert.assertEquals(deviceB, mHeadsetService.getActiveDevice());
        // Start voice recognition
        startVoiceRecognitionFromAg();
        // Start voice recognition from other device should fail
        HeadsetStackEvent startVrEventA =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED, deviceA);
        mHeadsetService.messageFromNative(startVrEventA);
        // TODO(b/79660380): Workaround in case voice recognition was not terminated properly
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).stopVoiceRecognition(deviceB);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).disconnectAudio(deviceB);
        // This request should still fail
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).atResponseCode(deviceA,
                HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        if (Flags.hfpCodecAptxVoice()) {
            verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).atLeast(1))
                    .enableSwb(
                            eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                            anyBoolean(),
                            eq(deviceB));
        }
        verifyNoMoreInteractions(mNativeInterface);
    }

    /**
     * Test to verify the following behavior regarding AG initiated voice recognition
     * in the device not active failure scenario
     *   1. AG starts voice recognition and notify the Bluetooth stack via
     *      {@link BluetoothHeadset#startVoiceRecognition(BluetoothDevice)} to indicate that voice
     *      recognition has started
     *   2. Device is not active, should do voice recognition on active device only
     *
     * Reference: Section 4.25, Page 64/144 of HFP 1.7.1 specification
     */
    @Test
    public void testVoiceRecognition_MultiAgInitiatedDeviceNotActive() {
        // Connect two devices
        BluetoothDevice deviceA = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(deviceA);
        BluetoothDevice deviceB = TestUtils.getTestDevice(mAdapter, 1);
        connectTestDevice(deviceB);
        InOrder inOrder = inOrder(mNativeInterface);
        inOrder.verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBsir(eq(deviceA), eq(true));
        inOrder.verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBsir(eq(deviceB), eq(false));
        inOrder.verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .sendBsir(eq(deviceA), eq(false));
        // Set active device to device B
        Assert.assertTrue(mHeadsetService.setActiveDevice(deviceB));
        verify(mNativeInterface).setActiveDevice(deviceB);
        Assert.assertEquals(deviceB, mHeadsetService.getActiveDevice());
        // Start voice recognition should succeed
        Assert.assertTrue(mHeadsetService.startVoiceRecognition(deviceA));
        verify(mNativeInterface).setActiveDevice(deviceA);
        Assert.assertEquals(deviceA, mHeadsetService.getActiveDevice());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).startVoiceRecognition(deviceA);
        verify(mAudioManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).setA2dpSuspended(true);
        verify(mAudioManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).setLeAudioSuspended(true);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).connectAudio(deviceA);
        waitAndVerifyAudioStateIntent(ASYNC_CALL_TIMEOUT_MILLIS, deviceA,
                BluetoothHeadset.STATE_AUDIO_CONNECTING, BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
        mHeadsetService.messageFromNative(
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED, deviceA));
        waitAndVerifyAudioStateIntent(ASYNC_CALL_TIMEOUT_MILLIS, deviceA,
                BluetoothHeadset.STATE_AUDIO_CONNECTED, BluetoothHeadset.STATE_AUDIO_CONNECTING);
        if (Flags.hfpCodecAptxVoice()) {
            verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).atLeast(1))
                    .enableSwb(
                            eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                            anyBoolean(),
                            eq(deviceA));
        }
        verifyNoMoreInteractions(mNativeInterface);
    }

    /**
     * Test to verify the call state and caller information are correctly delivered
     * {@link BluetoothHeadset#phoneStateChanged(int, int, int, String, int, String, boolean)}
     */
    @Test
    public void testPhoneStateChangedWithIncomingCallState() throws RemoteException {
        // Connect HF
        for (int i = 0; i < MAX_HEADSET_CONNECTIONS; ++i) {
            BluetoothDevice device = TestUtils.getTestDevice(mAdapter, i);
            connectTestDevice(device);
            Assert.assertThat(
                    mHeadsetService.getConnectedDevices(),
                    Matchers.containsInAnyOrder(mBondedDevices.toArray()));
            Assert.assertThat(
                    mHeadsetService.getDevicesMatchingConnectionStates(
                            new int[] {BluetoothProfile.STATE_CONNECTED}),
                    Matchers.containsInAnyOrder(mBondedDevices.toArray()));
        }
        List<BluetoothDevice> connectedDevices = mHeadsetService.getConnectedDevices();
        Assert.assertThat(connectedDevices, Matchers.containsInAnyOrder(mBondedDevices.toArray()));
        // Incoming call update by telecom
        mHeadsetService.phoneStateChanged(
                0,
                0,
                HeadsetHalConstants.CALL_STATE_INCOMING,
                TEST_PHONE_NUMBER,
                128,
                TEST_CALLER_ID,
                false);
        HeadsetCallState incomingCallState =
                new HeadsetCallState(
                        0,
                        0,
                        HeadsetHalConstants.CALL_STATE_INCOMING,
                        TEST_PHONE_NUMBER,
                        128,
                        TEST_CALLER_ID);
        verifyCallStateToNativeInvocation(incomingCallState, connectedDevices);
    }

    /**
     * Test to verify if AptX Voice codec is set properly within incoming call. AptX SWB and AptX
     * SWB PM are enabled, LC3 SWB is disabled. Voice call is non-HD and non Voip. Expected result:
     * AptX SWB codec disabled.
     */
    @Test
    public void testIncomingCall_NonHdNonVoipCall_AptXDisabled() {
        configureHeadsetServiceForAptxVoice(true);

        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);

        when(mNativeInterface.enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                        anyBoolean(),
                        eq(device)))
                .thenReturn(true);
        when(mSystemInterface.isHighDefCallInProgress()).thenReturn(false);

        // Connect HF
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        // Simulate AptX SWB enabled, LC3 SWB disabled
        int swbCodec = HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX;
        int swbConfig = HeadsetHalConstants.BTHF_SWB_YES;
        HeadsetStackEvent event =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB, swbCodec, swbConfig, device);
        mHeadsetService.messageFromNative(event);
        // Simulate incoming call
        mHeadsetService.phoneStateChanged(
                0,
                0,
                HeadsetHalConstants.CALL_STATE_INCOMING,
                TEST_PHONE_NUMBER,
                128,
                TEST_CALLER_ID,
                false);
        HeadsetCallState incomingCallState =
                new HeadsetCallState(
                        0,
                        0,
                        HeadsetHalConstants.CALL_STATE_INCOMING,
                        TEST_PHONE_NUMBER,
                        128,
                        TEST_CALLER_ID);
        List<BluetoothDevice> connectedDevices = mHeadsetService.getConnectedDevices();
        verifyCallStateToNativeInvocation(incomingCallState, connectedDevices);
        TestUtils.waitForLooperToFinishScheduledTask(
                mHeadsetService.getStateMachinesThreadLooper());
        when(mSystemInterface.isRinging()).thenReturn(true);
        // Connect Audio
        Assert.assertEquals(BluetoothStatusCodes.SUCCESS, mHeadsetService.connectAudio());
        waitAndVerifyAudioStateIntent(
                ASYNC_CALL_TIMEOUT_MILLIS,
                device,
                BluetoothHeadset.STATE_AUDIO_CONNECTING,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
        mHeadsetService.messageFromNative(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        device));
        waitAndVerifyAudioStateIntent(
                ASYNC_CALL_TIMEOUT_MILLIS,
                device,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTING);

        // Check that AptX SWB disabled, LC3 SWB disabled
        verifySetParametersToAudioSystemInvocation(false, false);
        verify(mNativeInterface, times(1)).connectAudio(eq(device));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).sendBsir(eq(device), eq(true));
        verify(mNativeInterface, times(2))
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX), eq(false), eq(device));
        verifyNoMoreInteractions(mNativeInterface);
        configureHeadsetServiceForAptxVoice(false);
    }

    /**
     * Test to verify if AptX Voice codec is set properly within incoming call. AptX SWB and AptX
     * SWB PM are enabled, LC3 SWB is disabled. Voice call is HD and non Voip. Expected result: AptX
     * SWB codec enabled.
     */
    @Test
    public void testIncomingCall_HdNonVoipCall_AptXEnabled() {
        configureHeadsetServiceForAptxVoice(true);
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);

        when(mNativeInterface.enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                        anyBoolean(),
                        eq(device)))
                .thenReturn(true);
        when(mSystemInterface.isHighDefCallInProgress()).thenReturn(true);

        // Connect HF
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        // Simulate AptX SWB enabled, LC3 SWB disabled
        int swbCodec = HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX;
        int swbConfig = HeadsetHalConstants.BTHF_SWB_YES;
        HeadsetStackEvent event =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB, swbCodec, swbConfig, device);
        mHeadsetService.messageFromNative(event);
        // Simulate incoming call
        mHeadsetService.phoneStateChanged(
                0,
                0,
                HeadsetHalConstants.CALL_STATE_INCOMING,
                TEST_PHONE_NUMBER,
                128,
                TEST_CALLER_ID,
                false);
        HeadsetCallState incomingCallState =
                new HeadsetCallState(
                        0,
                        0,
                        HeadsetHalConstants.CALL_STATE_INCOMING,
                        TEST_PHONE_NUMBER,
                        128,
                        TEST_CALLER_ID);
        List<BluetoothDevice> connectedDevices = mHeadsetService.getConnectedDevices();
        verifyCallStateToNativeInvocation(incomingCallState, connectedDevices);
        TestUtils.waitForLooperToFinishScheduledTask(
                mHeadsetService.getStateMachinesThreadLooper());
        when(mSystemInterface.isRinging()).thenReturn(true);
        // Connect Audio
        Assert.assertEquals(BluetoothStatusCodes.SUCCESS, mHeadsetService.connectAudio());
        waitAndVerifyAudioStateIntent(
                ASYNC_CALL_TIMEOUT_MILLIS,
                device,
                BluetoothHeadset.STATE_AUDIO_CONNECTING,
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
        mHeadsetService.messageFromNative(
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED,
                        device));
        waitAndVerifyAudioStateIntent(
                ASYNC_CALL_TIMEOUT_MILLIS,
                device,
                BluetoothHeadset.STATE_AUDIO_CONNECTED,
                BluetoothHeadset.STATE_AUDIO_CONNECTING);

        // Check that AptX SWB enabled, LC3 SWB disabled
        verifySetParametersToAudioSystemInvocation(false, true);
        verify(mNativeInterface, times(1)).connectAudio(eq(device));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).sendBsir(eq(device), eq(true));
        verify(mNativeInterface, times(2))
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX), eq(true), eq(device));
        verifyNoMoreInteractions(mNativeInterface);
        configureHeadsetServiceForAptxVoice(false);
    }

    /**
     * Test to verify if audio parameters are correctly set when AptX Voice feature present. Test
     * LC3 SWB enabled
     */
    @Test
    public void testSetAudioParametersWithAptxVoice_Lc3SwbEnabled() {
        configureHeadsetServiceForAptxVoice(true);
        // Connect HF
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).sendBsir(eq(device), eq(true));
        // Simulate SWB
        int swbCodec = HeadsetHalConstants.BTHF_SWB_CODEC_LC3;
        int swbConfig = HeadsetHalConstants.BTHF_SWB_YES;
        HeadsetStackEvent event =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB, swbCodec, swbConfig, device);
        mHeadsetService.messageFromNative(event);
        // Start voice recognition
        startVoiceRecognitionFromHf(device);
        // Check that proper codecs were set
        verifySetParametersToAudioSystemInvocation(true, false);
        configureHeadsetServiceForAptxVoice(false);
    }

    /**
     * Test to verify if audio parameters are correctly set when AptX Voice feature not present.
     * Test LC3 SWB enabled
     */
    @Test
    public void testSetAudioParametersWithoutAptxVoice_Lc3SwbEnabled() {
        configureHeadsetServiceForAptxVoice(false);
        // Connect HF
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).sendBsir(eq(device), eq(true));
        // Simulate SWB
        int swbCodec = HeadsetHalConstants.BTHF_SWB_CODEC_LC3;
        int swbConfig = HeadsetHalConstants.BTHF_SWB_YES;
        HeadsetStackEvent event =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB, swbCodec, swbConfig, device);
        mHeadsetService.messageFromNative(event);
        // Start voice recognition
        startVoiceRecognitionFromHf(device);
        // Check that proper codecs were set
        verifySetParametersToAudioSystemInvocation(true, false);
    }

    /**
     * Test to verify if audio parameters are correctly set when AptX Voice feature present. Test
     * aptX SWB enabled
     */
    @Test
    public void testSetAudioParametersWithAptxVoice_AptXSwbEnabled() {
        configureHeadsetServiceForAptxVoice(true);
        // Connect HF
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).sendBsir(eq(device), eq(true));
        // Simulate SWB
        int swbCodec = HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX;
        int swbConfig = HeadsetHalConstants.BTHF_SWB_YES;
        HeadsetStackEvent event =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_SWB, swbCodec, swbConfig, device);
        mHeadsetService.messageFromNative(event);
        // Start voice recognition
        startVoiceRecognitionFromHf(device);
        // Check that proper codecs were set
        verifySetParametersToAudioSystemInvocation(false, true);
        configureHeadsetServiceForAptxVoice(false);
    }

    /**
     * Test to verify if audio parameters are correctly set when AptX Voice feature present. Test
     * SWB disabled
     */
    @Test
    public void testSetAudioParametersWithAptxVoice_SwbDisabled() {
        configureHeadsetServiceForAptxVoice(true);
        // Connect HF
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).sendBsir(eq(device), eq(true));
        // Simulate SWB
        int codec = HeadsetHalConstants.BTHF_SWB_NO;
        HeadsetStackEvent event =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_SWB, codec, device);
        mHeadsetService.messageFromNative(event);
        // Start voice recognition
        startVoiceRecognitionFromHf(device);
        // Check that proper codecs were set
        verifySetParametersToAudioSystemInvocation(false, false);
        configureHeadsetServiceForAptxVoice(false);
    }

    /**
     * Test to verify if audio parameters are correctly set when AptX Voice feature not present.
     * Test SWB disabled
     */
    @Test
    public void testSetAudioParametersWithoutAptxVoice_SwbDisabled() {
        configureHeadsetServiceForAptxVoice(false);
        // Connect HF
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).sendBsir(eq(device), eq(true));
        // Simulate SWB
        int codec = HeadsetHalConstants.BTHF_SWB_NO;
        HeadsetStackEvent event =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_SWB, codec, device);
        mHeadsetService.messageFromNative(event);
        // Start voice recognition
        startVoiceRecognitionFromHf(device);
        // Check that proper codecs were set
        verifySetParametersToAudioSystemInvocation(false, false);
    }

    /**
     * Test the functionality of {@link HeadsetService#enableSwbCodec()}
     *
     * <p>AptX SWB and AptX SWB PM enabled
     */
    @Test
    public void testVoiceRecognition_AptXSwbEnabled() {
        configureHeadsetServiceForAptxVoice(true);
        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);

        // Connect HF
        connectTestDevice(device);
        // Make device active
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));
        verify(mNativeInterface).setActiveDevice(device);
        Assert.assertEquals(device, mHeadsetService.getActiveDevice());
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).sendBsir(eq(device), eq(true));
        // Start voice recognition to connect audio
        startVoiceRecognitionFromHf(device);

        verify(mNativeInterface, times(2))
                .enableSwb(
                        eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX), eq(true), eq(device));
        configureHeadsetServiceForAptxVoice(false);
    }

    private void startVoiceRecognitionFromHf(BluetoothDevice device) {
        // Start voice recognition
        HeadsetStackEvent startVrEvent =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED, device);
        mHeadsetService.messageFromNative(startVrEvent);
        verify(mSystemInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).activateVoiceRecognition();
        Assert.assertTrue(mHeadsetService.startVoiceRecognition(device));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        verify(mAudioManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).setA2dpSuspended(true);
        verify(mAudioManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).setLeAudioSuspended(true);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).connectAudio(device);
        if (Flags.hfpCodecAptxVoice()) {
            verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).atLeast(1))
                    .enableSwb(
                            eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                            anyBoolean(),
                            eq(device));
        }
        waitAndVerifyAudioStateIntent(ASYNC_CALL_TIMEOUT_MILLIS, device,
                BluetoothHeadset.STATE_AUDIO_CONNECTING, BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
        mHeadsetService.messageFromNative(
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED, device));
        waitAndVerifyAudioStateIntent(ASYNC_CALL_TIMEOUT_MILLIS, device,
                BluetoothHeadset.STATE_AUDIO_CONNECTED, BluetoothHeadset.STATE_AUDIO_CONNECTING);
        verifyNoMoreInteractions(mNativeInterface);
    }

    private void startVoiceRecognitionFromHf_ScoManagedByAudio(BluetoothDevice device) {
        if (!Flags.isScoManagedByAudio()) {
            Log.i(TAG, "isScoManagedByAudio is disabled");
            return;
        }
        // Start voice recognition
        HeadsetStackEvent startVrEvent =
                new HeadsetStackEvent(
                        HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                        HeadsetHalConstants.VR_STATE_STARTED,
                        device);
        mHeadsetService.messageFromNative(startVrEvent);
        verify(mSystemInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).activateVoiceRecognition();
        // has not add verification AudioDeviceInfo because it is final, unless add a wrapper
        mHeadsetService.startVoiceRecognition(device);
        verify(mAudioManager, times(0)).setA2dpSuspended(true);
        verify(mAudioManager, times(0)).setLeAudioSuspended(true);
        verify(mNativeInterface, times(0)).connectAudio(device);
    }

    private void startVoiceRecognitionFromAg() {
        BluetoothDevice device = mHeadsetService.getActiveDevice();
        Assert.assertNotNull(device);
        Assert.assertTrue(mHeadsetService.startVoiceRecognition(device));
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).startVoiceRecognition(device);
        verify(mAudioManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).setA2dpSuspended(true);
        verify(mAudioManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).setLeAudioSuspended(true);
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).connectAudio(device);
        if (Flags.hfpCodecAptxVoice()) {
            verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS).atLeast(1))
                    .enableSwb(
                            eq(HeadsetHalConstants.BTHF_SWB_CODEC_VENDOR_APTX),
                            anyBoolean(),
                            eq(device));
        }
        waitAndVerifyAudioStateIntent(ASYNC_CALL_TIMEOUT_MILLIS, device,
                BluetoothHeadset.STATE_AUDIO_CONNECTING, BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
        mHeadsetService.messageFromNative(
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                        HeadsetHalConstants.AUDIO_STATE_CONNECTED, device));
        waitAndVerifyAudioStateIntent(ASYNC_CALL_TIMEOUT_MILLIS, device,
                BluetoothHeadset.STATE_AUDIO_CONNECTED, BluetoothHeadset.STATE_AUDIO_CONNECTING);
        verifyNoMoreInteractions(mNativeInterface);
    }

    private void startVoiceRecognitionFromAg_ScoManagedByAudio() {
        BluetoothDevice device = mHeadsetService.getActiveDevice();
        Assert.assertNotNull(device);
        mHeadsetService.startVoiceRecognition(device);
        // has not add verification AudioDeviceInfo because it is final, unless add a wrapper
        verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).startVoiceRecognition(device);
        verify(mAudioManager, times(0)).setA2dpSuspended(true);
        verify(mAudioManager, times(0)).setLeAudioSuspended(true);
        verify(mNativeInterface, times(0)).connectAudio(device);
    }

    /**
     * Test to verify the following behavior regarding phoneStateChanged when the SCO is managed by
     * the Audio: When phoneStateChange returns, HeadsetStateMachine completes processing
     * mActiveDevice's CALL_STATE_CHANGED message
     */
    @Test
    public void testPhoneStateChange_SynchronousCallStateChanged() {
        mSetFlagsRule.enableFlags(Flags.FLAG_IS_SCO_MANAGED_BY_AUDIO);
        Utils.setIsScoManagedByAudioEnabled(true);

        BluetoothDevice device = TestUtils.getTestDevice(mAdapter, 0);
        Assert.assertNotNull(device);
        connectTestDevice(device);

        BluetoothDevice device2 = TestUtils.getTestDevice(mAdapter, 1);
        Assert.assertNotNull(device2);
        connectTestDevice(device2);

        BluetoothDevice device3 = TestUtils.getTestDevice(mAdapter, 2);
        Assert.assertNotNull(device3);
        connectTestDevice(device3);

        mHeadsetService.setActiveDevice(device);
        Assert.assertTrue(mHeadsetService.setActiveDevice(device));

        HeadsetCallState headsetCallState =
                new HeadsetCallState(
                        0, 0, HeadsetHalConstants.CALL_STATE_INCOMING, TEST_PHONE_NUMBER, 128, "");
        mHeadsetService.phoneStateChanged(
                headsetCallState.mNumActive,
                headsetCallState.mNumHeld,
                headsetCallState.mCallState,
                headsetCallState.mNumber,
                headsetCallState.mType,
                headsetCallState.mName,
                false);
        // verify phoneStateChanged runs synchronously, which means when phoneStateChange returns,
        // HeadsetStateMachine completes processing CALL_STATE_CHANGED message
        verify(mNativeInterface, times(1)).phoneStateChange(device, headsetCallState);

        Utils.setIsScoManagedByAudioEnabled(false);
    }

    private void connectTestDevice(BluetoothDevice device) {
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.HEADSET))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService)
                .getBondState(eq(device));
        // Make device bonded
        mBondedDevices.add(device);
        // Use connecting event to indicate that device is connecting
        HeadsetStackEvent rfcommConnectedEvent =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_CONNECTED, device);
        mHeadsetService.messageFromNative(rfcommConnectedEvent);
        verify(mObjectsFactory).makeStateMachine(device,
                mHeadsetService.getStateMachinesThreadLooper(), mHeadsetService, mAdapterService,
                mNativeInterface, mSystemInterface);
        verify(mActiveDeviceManager, timeout(STATE_CHANGE_TIMEOUT_MILLIS))
                .profileConnectionStateChanged(
                        BluetoothProfile.HEADSET,
                        device,
                        BluetoothProfile.STATE_DISCONNECTED,
                        BluetoothProfile.STATE_CONNECTING);
        verify(mSilenceDeviceManager, timeout(STATE_CHANGE_TIMEOUT_MILLIS))
                .hfpConnectionStateChanged(
                        device,
                        BluetoothProfile.STATE_DISCONNECTED,
                        BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING,
                mHeadsetService.getConnectionState(device));
        Assert.assertEquals(Collections.singletonList(device),
                mHeadsetService.getDevicesMatchingConnectionStates(
                        new int[]{BluetoothProfile.STATE_CONNECTING}));
        // Get feedback from native to put device into connected state
        HeadsetStackEvent slcConnectedEvent =
                new HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                        HeadsetHalConstants.CONNECTION_STATE_SLC_CONNECTED, device);
        mHeadsetService.messageFromNative(slcConnectedEvent);
        verify(mActiveDeviceManager, timeout(STATE_CHANGE_TIMEOUT_MILLIS))
                .profileConnectionStateChanged(
                        BluetoothProfile.HEADSET,
                        device,
                        BluetoothProfile.STATE_CONNECTING,
                        BluetoothProfile.STATE_CONNECTED);
        verify(mSilenceDeviceManager, timeout(STATE_CHANGE_TIMEOUT_MILLIS))
                .hfpConnectionStateChanged(
                        device,
                        BluetoothProfile.STATE_CONNECTING,
                        BluetoothProfile.STATE_CONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED,
                mHeadsetService.getConnectionState(device));
    }

    private void waitAndVerifyConnectionStateIntent(int timeoutMs, BluetoothDevice device,
            int newState, int prevState) {
        Intent intent = TestUtils.waitForIntent(timeoutMs, mConnectionStateChangedQueue);
        Assert.assertNotNull(intent);
        HeadsetTestUtils.verifyConnectionStateBroadcast(device, newState, prevState, intent, false);
    }

    private void waitAndVerifyActiveDeviceChangedIntent(int timeoutMs, BluetoothDevice device) {
        Intent intent = TestUtils.waitForIntent(timeoutMs, mActiveDeviceChangedQueue);
        Assert.assertNotNull(intent);
        HeadsetTestUtils.verifyActiveDeviceChangedBroadcast(device, intent, false);
    }

    private void waitAndVerifyAudioStateIntent(int timeoutMs, BluetoothDevice device, int newState,
            int prevState) {
        Intent intent = TestUtils.waitForIntent(timeoutMs, mAudioStateChangedQueue);
        Assert.assertNotNull(intent);
        HeadsetTestUtils.verifyAudioStateBroadcast(device, newState, prevState, intent);
    }

    /**
     * Verify the series of invocations after
     * {@link BluetoothHeadset#startScoUsingVirtualVoiceCall()}
     *
     * @param connectedDevices must be in the same sequence as
     * {@link BluetoothHeadset#getConnectedDevices()}
     */
    private void verifyVirtualCallStartSequenceInvocations(List<BluetoothDevice> connectedDevices) {
        // Do not verify HeadsetPhoneState changes as it is verified in HeadsetServiceTest
        verifyCallStateToNativeInvocation(
                new HeadsetCallState(0, 0, HeadsetHalConstants.CALL_STATE_DIALING, "", 0, ""),
                connectedDevices);
        verifyCallStateToNativeInvocation(
                new HeadsetCallState(0, 0, HeadsetHalConstants.CALL_STATE_ALERTING, "", 0, ""),
                connectedDevices);
        verifyCallStateToNativeInvocation(
                new HeadsetCallState(1, 0, HeadsetHalConstants.CALL_STATE_IDLE, "", 0, ""),
                connectedDevices);
    }

    private void verifyVirtualCallStopSequenceInvocations(List<BluetoothDevice> connectedDevices) {
        verifyCallStateToNativeInvocation(
                new HeadsetCallState(0, 0, HeadsetHalConstants.CALL_STATE_IDLE, "", 0, ""),
                connectedDevices);
    }

    private void verifyCallStateToNativeInvocation(HeadsetCallState headsetCallState,
            List<BluetoothDevice> connectedDevices) {
        for (BluetoothDevice device : connectedDevices) {
            verify(mNativeInterface, timeout(ASYNC_CALL_TIMEOUT_MILLIS)).phoneStateChange(device,
                    headsetCallState);
        }
    }

    private void verifySetParametersToAudioSystemInvocation(
            boolean lc3Enabled, boolean aptxEnabled) {
        verify(mAudioManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                .setParameters(lc3Enabled ? "bt_lc3_swb=on" : "bt_lc3_swb=off");
        if (Flags.hfpCodecAptxVoice()) {
            verify(mAudioManager, timeout(ASYNC_CALL_TIMEOUT_MILLIS))
                    .setParameters(aptxEnabled ? "bt_swb=0" : "bt_swb=65535");
        }
    }

    private void setAptxVoiceSystemProperties(
            boolean aptx_voice, boolean aptx_voice_power_management) {
        SystemProperties.set(
                "bluetooth.hfp.codec_aptx_voice.enabled", (aptx_voice ? "true" : "false"));
        Assert.assertEquals(
                SystemProperties.getBoolean("bluetooth.hfp.codec_aptx_voice.enabled", false),
                aptx_voice);
        SystemProperties.set(
                "bluetooth.hfp.swb.aptx.power_management.enabled",
                (aptx_voice_power_management ? "true" : "false"));
        Assert.assertEquals(
                SystemProperties.getBoolean(
                        "bluetooth.hfp.swb.aptx.power_management.enabled", false),
                aptx_voice_power_management);
    }

    private void configureHeadsetServiceForAptxVoice(boolean enable) {
        if (enable) {
            mSetFlagsRule.enableFlags(Flags.FLAG_HFP_CODEC_APTX_VOICE);
            Assert.assertTrue(Flags.hfpCodecAptxVoice());
        } else {
            mSetFlagsRule.disableFlags(Flags.FLAG_HFP_CODEC_APTX_VOICE);
            Assert.assertFalse(Flags.hfpCodecAptxVoice());
        }
        setAptxVoiceSystemProperties(enable, enable);
        mHeadsetService.mIsAptXSwbEnabled = enable;
        Assert.assertEquals(mHeadsetService.isAptXSwbEnabled(), enable);
        mHeadsetService.mIsAptXSwbPmEnabled = enable;
        Assert.assertEquals(mHeadsetService.isAptXSwbPmEnabled(), enable);
    }
}
