/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Motorola, Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.opp;

import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.BluetoothMetricsProto;
import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.content_profiles.ContentProfileErrorReportUtils;
import com.android.obex.ClientOperation;
import com.android.obex.ClientSession;
import com.android.obex.HeaderSet;
import com.android.obex.ObexTransport;
import com.android.obex.ResponseCodes;

import com.google.common.annotations.VisibleForTesting;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** This class runs as an OBEX client */
// Next tag value for ContentProfileErrorReportUtils.report(): 17
public class BluetoothOppObexClientSession implements BluetoothOppObexSession {

    private static final String TAG = "BtOppObexClient";

    private ClientThread mThread;

    private ObexTransport mTransport;

    private Context mContext;

    private volatile boolean mInterrupted;

    @VisibleForTesting
    volatile boolean mWaitingForRemote;

    private int mNumFilesAttemptedToSend;

    public BluetoothOppObexClientSession(Context context, ObexTransport transport) {
        if (transport == null) {
            throw new NullPointerException("transport is null");
        }
        mContext = context;
        mTransport = transport;
    }

    @Override
    public void start(Handler handler, int numShares) {
        Log.d(TAG, "Start!");
        mThread = new ClientThread(mContext, mTransport, numShares, handler);
        mThread.start();
    }

    @Override
    public void stop() {
        Log.d(TAG, "Stop!");
        if (mThread != null) {
            mInterrupted = true;
            Log.v(TAG, "Interrupt thread to terminate it");
            mThread.interrupt();
            mThread = null;
        }
        BluetoothOppUtility.cancelNotification(mContext);
    }

    @Override
    public void addShare(BluetoothOppShareInfo share) {
        mThread.addShare(share);
    }

    @VisibleForTesting
    static int readFully(InputStream is, byte[] buffer, int size) throws IOException {
        int done = 0;
        while (done < size) {
            int got = is.read(buffer, done, size - done);
            if (got <= 0) {
                break;
            }
            done += got;
        }
        return done;
    }

    @VisibleForTesting
    class ClientThread extends Thread {

        private static final int SLEEP_TIME = 500;

        private Context mContext1;

        private BluetoothOppShareInfo mInfo;

        private volatile boolean mWaitingForShare;

        private ObexTransport mTransport1;

        @VisibleForTesting
        ClientSession mCs;

        private WakeLock mWakeLock;

        private BluetoothOppSendFileInfo mFileInfo = null;

        private boolean mConnected = false;

        private int mNumShares;
        private final Handler mCallbackHandler;

        ClientThread(
                Context context, ObexTransport transport, int initialNumShares, Handler callback) {
            super("BtOpp ClientThread");
            mContext1 = context;
            mTransport1 = transport;
            mWaitingForShare = true;
            mWaitingForRemote = false;
            mNumShares = initialNumShares;
            PowerManager pm = mContext.getSystemService(PowerManager.class);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            mCallbackHandler = callback;
        }

        public void addShare(BluetoothOppShareInfo info) {
            mInfo = info;
            mFileInfo = processShareInfo();
            mWaitingForShare = false;
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            Log.v(TAG, "acquire partial WakeLock");
            mWakeLock.acquire();

            try {
                Thread.sleep(100);
            } catch (InterruptedException e1) {
                ContentProfileErrorReportUtils.report(
                        BluetoothProfile.OPP,
                        BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                        BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                        0);
                Log.v(TAG, "Client thread was interrupted (1), exiting");
                mInterrupted = true;
            }
            if (!mInterrupted) {
                connect(mNumShares);
            }

            mNumFilesAttemptedToSend = 0;
            while (!mInterrupted) {
                if (!mWaitingForShare) {
                    doSend();
                } else {
                    try {
                        Log.d(TAG, "Client thread waiting for next share, sleep for "
                                + SLEEP_TIME);
                        Thread.sleep(SLEEP_TIME);
                    } catch (InterruptedException e) {
                        ContentProfileErrorReportUtils.report(
                                BluetoothProfile.OPP,
                                BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                                BluetoothStatsLog
                                        .BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                                1);
                    }
                }
            }
            disconnect();

            if (mWakeLock.isHeld()) {
                Log.v(TAG, "release partial WakeLock");
                mWakeLock.release();
            }

            if (mNumFilesAttemptedToSend > 0) {
                // Log outgoing OPP transfer if more than one file is accepted by remote
                MetricsLogger.logProfileConnectionEvent(BluetoothMetricsProto.ProfileId.OPP);
            }
            Message msg = Message.obtain(mCallbackHandler);
            msg.what = BluetoothOppObexSession.MSG_SESSION_COMPLETE;
            msg.obj = mInfo;
            msg.sendToTarget();
        }

        private void disconnect() {
            try {
                if (mCs != null) {
                    mCs.disconnect(null);
                }
                mCs = null;
                Log.d(TAG, "OBEX session disconnected");
            } catch (IOException e) {
                ContentProfileErrorReportUtils.report(
                        BluetoothProfile.OPP,
                        BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                        BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                        2);
                Log.w(TAG, "OBEX session disconnect error" + e);
            }
            try {
                if (mCs != null) {
                    Log.d(TAG, "OBEX session close mCs");
                    mCs.close();
                    Log.d(TAG, "OBEX session closed");
                }
            } catch (IOException e) {
                ContentProfileErrorReportUtils.report(
                        BluetoothProfile.OPP,
                        BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                        BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                        3);
                Log.w(TAG, "OBEX session close error" + e);
            }
            if (mTransport1 != null) {
                try {
                    mTransport1.close();
                } catch (IOException e) {
                    ContentProfileErrorReportUtils.report(
                            BluetoothProfile.OPP,
                            BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                            BluetoothStatsLog
                                    .BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                            4);
                    Log.e(TAG, "mTransport.close error");
                }

            }
        }

        private void connect(int numShares) {
            Log.d(TAG, "Create ClientSession with transport " + mTransport1.toString());
            try {
                mCs = new ClientSession(mTransport1);
                mConnected = true;
            } catch (IOException e1) {
                ContentProfileErrorReportUtils.report(
                        BluetoothProfile.OPP,
                        BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                        BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                        5);
                Log.e(TAG, "OBEX session create error");
            }
            if (mConnected) {
                mConnected = false;
                HeaderSet hs = new HeaderSet();
                hs.setHeader(HeaderSet.COUNT, (long) numShares);
                synchronized (this) {
                    mWaitingForRemote = true;
                }
                try {
                    mCs.connect(hs);
                    Log.d(TAG, "OBEX session created");
                    mConnected = true;
                } catch (IOException e) {
                    ContentProfileErrorReportUtils.report(
                            BluetoothProfile.OPP,
                            BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                            BluetoothStatsLog
                                    .BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                            6);
                    Log.e(TAG, "OBEX session connect error");
                }
            }
            synchronized (this) {
                mWaitingForRemote = false;
            }
        }

        private void doSend() {

            int status = BluetoothShare.STATUS_SUCCESS;

            /* connection is established too fast to get first mInfo */
            while (mFileInfo == null) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    ContentProfileErrorReportUtils.report(
                            BluetoothProfile.OPP,
                            BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                            BluetoothStatsLog
                                    .BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                            7);
                    status = BluetoothShare.STATUS_CANCELED;
                }
            }
            if (!mConnected) {
                // Obex connection error
                status = BluetoothShare.STATUS_CONNECTION_ERROR;
            }
            if (status == BluetoothShare.STATUS_SUCCESS) {
                /* do real send */
                if (mFileInfo.mFileName != null) {
                    status = sendFile(mFileInfo);
                } else {
                    /* this is invalid request */
                    status = mFileInfo.mStatus;
                }
                mWaitingForShare = true;
            } else {
                Constants.updateShareStatus(mContext1, mInfo.mId, status);
            }

            Message msg = Message.obtain(mCallbackHandler);
            msg.what = (status == BluetoothShare.STATUS_SUCCESS)
                    ? BluetoothOppObexSession.MSG_SHARE_COMPLETE
                    : BluetoothOppObexSession.MSG_SESSION_ERROR;
            mInfo.mStatus = status;
            msg.obj = mInfo;
            msg.sendToTarget();
        }

        /*
         * Validate this ShareInfo
         */
        private BluetoothOppSendFileInfo processShareInfo() {
            Log.v(TAG, "Client thread processShareInfo() " + mInfo.mId);

            BluetoothOppSendFileInfo fileInfo = BluetoothOppUtility.getSendFileInfo(mInfo.mUri);
            if (fileInfo.mFileName == null || fileInfo.mLength == 0) {
                Log.v(TAG, "BluetoothOppSendFileInfo get invalid file");
                Constants.updateShareStatus(mContext1, mInfo.mId, fileInfo.mStatus);

            } else {
                Log.v(TAG, "Generate BluetoothOppSendFileInfo:");
                Log.v(TAG, "filename  :" + fileInfo.mFileName);
                Log.v(TAG, "length    :" + fileInfo.mLength);
                Log.v(TAG, "mimetype  :" + fileInfo.mMimetype);

                ContentValues updateValues = new ContentValues();
                Uri contentUri = Uri.parse(BluetoothShare.CONTENT_URI + "/" + mInfo.mId);

                updateValues.put(BluetoothShare.FILENAME_HINT, fileInfo.mFileName);
                updateValues.put(BluetoothShare.TOTAL_BYTES, fileInfo.mLength);
                updateValues.put(BluetoothShare.MIMETYPE, fileInfo.mMimetype);
                BluetoothMethodProxy.getInstance().contentResolverUpdate(
                        mContext1.getContentResolver(), contentUri, updateValues, null, null);

            }
            return fileInfo;
        }

        @VisibleForTesting
        int sendFile(BluetoothOppSendFileInfo fileInfo) {
            boolean error = false;
            int responseCode = -1;
            long position = 0;
            int status = BluetoothShare.STATUS_SUCCESS;
            Uri contentUri = Uri.parse(BluetoothShare.CONTENT_URI + "/" + mInfo.mId);
            ContentValues updateValues;
            HeaderSet request = new HeaderSet();
            ClientOperation putOperation = null;
            OutputStream outputStream = null;
            InputStream inputStream = null;
            try {
                synchronized (this) {
                    mWaitingForRemote = true;
                }
                try {
                    Log.v(TAG, "Set header items for " + fileInfo.mFileName);
                    request.setHeader(HeaderSet.NAME, fileInfo.mFileName);
                    request.setHeader(HeaderSet.TYPE, fileInfo.mMimetype);

                    applyRemoteDeviceQuirks(request, mInfo.mDestination, fileInfo.mFileName);
                    Constants.updateShareStatus(mContext1, mInfo.mId,
                            BluetoothShare.STATUS_RUNNING);

                    request.setHeader(HeaderSet.LENGTH, fileInfo.mLength);

                    Log.v(TAG, "put headerset for " + fileInfo.mFileName);
                    putOperation = (ClientOperation) mCs.put(request);
                } catch (IllegalArgumentException e) {
                    ContentProfileErrorReportUtils.report(
                            BluetoothProfile.OPP,
                            BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                            BluetoothStatsLog
                                    .BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                            8);
                    status = BluetoothShare.STATUS_OBEX_DATA_ERROR;
                    Constants.updateShareStatus(mContext1, mInfo.mId, status);

                    Log.e(TAG, "Error setting header items for request: " + e);
                    error = true;
                } catch (IOException e) {
                    ContentProfileErrorReportUtils.report(
                            BluetoothProfile.OPP,
                            BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                            BluetoothStatsLog
                                    .BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                            9);
                    status = BluetoothShare.STATUS_OBEX_DATA_ERROR;
                    Constants.updateShareStatus(mContext1, mInfo.mId, status);

                    Log.e(TAG, "Error when put HeaderSet ");
                    error = true;
                }
                synchronized (this) {
                    mWaitingForRemote = false;
                }

                if (!error) {
                    try {
                        Log.v(TAG, "openOutputStream " + fileInfo.mFileName);
                        outputStream = putOperation.openOutputStream();
                        inputStream = putOperation.openInputStream();
                    } catch (IOException e) {
                        ContentProfileErrorReportUtils.report(
                                BluetoothProfile.OPP,
                                BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                                BluetoothStatsLog
                                        .BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                                10);
                        status = BluetoothShare.STATUS_OBEX_DATA_ERROR;
                        Constants.updateShareStatus(mContext1, mInfo.mId, status);
                        Log.e(TAG, "Error when openOutputStream");
                        error = true;
                    }
                }
                if (!error) {
                    updateValues = new ContentValues();
                    updateValues.put(BluetoothShare.CURRENT_BYTES, 0);
                    updateValues.put(BluetoothShare.STATUS, BluetoothShare.STATUS_RUNNING);
                    mContext1.getContentResolver().update(contentUri, updateValues, null, null);
                }

                if (!error) {
                    int readLength = 0;
                    long percent = 0;
                    long prevPercent = 0;
                    boolean okToProceed = false;
                    long timestamp = 0;
                    long currentTime = 0;
                    long prevTimestamp = SystemClock.elapsedRealtime();
                    int outputBufferSize = putOperation.getMaxPacketSize();
                    byte[] buffer = new byte[outputBufferSize];
                    BufferedInputStream a = new BufferedInputStream(fileInfo.mInputStream, 0x4000);

                    if (!mInterrupted && (position != fileInfo.mLength)) {
                        readLength = readFully(a, buffer, outputBufferSize);

                        mCallbackHandler.sendMessageDelayed(
                                mCallbackHandler.obtainMessage(
                                        BluetoothOppObexSession.MSG_CONNECT_TIMEOUT),
                                BluetoothOppObexSession.SESSION_TIMEOUT);
                        synchronized (this) {
                            mWaitingForRemote = true;
                        }

                        // first packet will block here
                        outputStream.write(buffer, 0, readLength);

                        position += readLength;

                        if (position == fileInfo.mLength) {
                            // if file length is smaller than buffer size, only one packet
                            // so block point is here
                            outputStream.close();
                            outputStream = null;
                        }

                        /* check remote accept or reject */
                        responseCode = putOperation.getResponseCode();

                        mCallbackHandler.removeMessages(
                                BluetoothOppObexSession.MSG_CONNECT_TIMEOUT);
                        synchronized (this) {
                            mWaitingForRemote = false;
                        }

                        if (responseCode == ResponseCodes.OBEX_HTTP_CONTINUE
                                || responseCode == ResponseCodes.OBEX_HTTP_OK) {
                            Log.v(TAG, "Remote accept");
                            okToProceed = true;
                            updateValues = new ContentValues();
                            updateValues.put(BluetoothShare.CURRENT_BYTES, position);
                            mContext1.getContentResolver()
                                    .update(contentUri, updateValues, null, null);
                            mNumFilesAttemptedToSend++;
                        } else {
                            Log.i(TAG, "Remote reject, Response code is " + responseCode);
                        }
                    }

                    while (!mInterrupted && okToProceed && (position < fileInfo.mLength)) {
                        timestamp = SystemClock.elapsedRealtime();

                        readLength = a.read(buffer, 0, outputBufferSize);
                        outputStream.write(buffer, 0, readLength);

                        /* check remote abort */
                        responseCode = putOperation.getResponseCode();
                        Log.v(TAG, "Response code is " + responseCode);
                        if (responseCode != ResponseCodes.OBEX_HTTP_CONTINUE
                                && responseCode != ResponseCodes.OBEX_HTTP_OK) {
                            /* abort happens */
                            okToProceed = false;
                        } else {
                            position += readLength;
                            currentTime = SystemClock.elapsedRealtime();
                            Log.v(TAG, "Sending file position = " + position
                                    + " readLength " + readLength + " bytes took "
                                    + (currentTime - timestamp) + " ms");
                            // Update the Progress Bar only if there is change in percentage
                            // or once per a period to notify NFC of this transfer is still alive
                            percent = position * 100 / fileInfo.mLength;
                            if (percent > prevPercent
                                    || currentTime - prevTimestamp > Constants.NFC_ALIVE_CHECK_MS) {
                                updateValues = new ContentValues();
                                updateValues.put(BluetoothShare.CURRENT_BYTES, position);
                                mContext1.getContentResolver()
                                        .update(contentUri, updateValues, null, null);
                                prevPercent = percent;
                                prevTimestamp = currentTime;
                            }
                        }
                    }

                    if (responseCode == ResponseCodes.OBEX_HTTP_FORBIDDEN
                            || responseCode == ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE) {
                        Log.i(TAG, "Remote reject file " + fileInfo.mFileName + " length "
                                + fileInfo.mLength);
                        status = BluetoothShare.STATUS_FORBIDDEN;
                    } else if (responseCode == ResponseCodes.OBEX_HTTP_UNSUPPORTED_TYPE) {
                        Log.i(TAG, "Remote reject file type " + fileInfo.mMimetype);
                        status = BluetoothShare.STATUS_NOT_ACCEPTABLE;
                    } else if (!mInterrupted && position == fileInfo.mLength) {
                        Log.i(TAG,
                                "SendFile finished send out file " + fileInfo.mFileName + " length "
                                        + fileInfo.mLength);
                    } else {
                        error = true;
                        status = BluetoothShare.STATUS_CANCELED;
                        putOperation.abort();
                        /* interrupted */
                        Log.i(TAG, "SendFile interrupted when send out file " + fileInfo.mFileName
                                + " at " + position + " of " + fileInfo.mLength);
                    }
                }
            } catch (IOException e) {
                ContentProfileErrorReportUtils.report(
                        BluetoothProfile.OPP,
                        BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                        BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                        11);
                handleSendException(e.toString());
            } catch (NullPointerException e) {
                ContentProfileErrorReportUtils.report(
                        BluetoothProfile.OPP,
                        BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                        BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                        12);
                handleSendException(e.toString());
            } catch (IndexOutOfBoundsException e) {
                ContentProfileErrorReportUtils.report(
                        BluetoothProfile.OPP,
                        BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                        BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                        13);
                handleSendException(e.toString());
            } finally {
                try {
                    if (outputStream != null) {
                        outputStream.close();
                    }
                } catch (IOException e) {
                    ContentProfileErrorReportUtils.report(
                            BluetoothProfile.OPP,
                            BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                            BluetoothStatsLog
                                    .BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                            14);
                    Log.e(TAG, "Error when closing output stream after send");
                }

                // Close InputStream and remove SendFileInfo from map
                BluetoothOppUtility.closeSendFileInfo(mInfo.mUri);
                try {
                    if (!error) {
                        responseCode = putOperation.getResponseCode();
                        if (responseCode != -1) {
                            Log.v(TAG, "Get response code " + responseCode);
                            if (responseCode != ResponseCodes.OBEX_HTTP_OK) {
                                Log.i(TAG, "Response error code is " + responseCode);
                                status = BluetoothShare.STATUS_UNHANDLED_OBEX_CODE;
                                if (responseCode == ResponseCodes.OBEX_HTTP_UNSUPPORTED_TYPE) {
                                    status = BluetoothShare.STATUS_NOT_ACCEPTABLE;
                                }
                                if (responseCode == ResponseCodes.OBEX_HTTP_FORBIDDEN
                                        || responseCode == ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE) {
                                    status = BluetoothShare.STATUS_FORBIDDEN;
                                }
                            }
                        } else {
                            // responseCode is -1, which means connection error
                            status = BluetoothShare.STATUS_CONNECTION_ERROR;
                        }
                    }

                    Constants.updateShareStatus(mContext1, mInfo.mId, status);

                    if (inputStream != null) {
                        inputStream.close();
                    }
                    if (putOperation != null) {
                        putOperation.close();
                    }
                } catch (IOException e) {
                    ContentProfileErrorReportUtils.report(
                            BluetoothProfile.OPP,
                            BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                            BluetoothStatsLog
                                    .BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                            15);
                    Log.e(TAG, "Error when closing stream after send");

                    // Socket has been closed due to the response timeout in the framework,
                    // mark the transfer as failure.
                    if (position != fileInfo.mLength) {
                        status = BluetoothShare.STATUS_FORBIDDEN;
                        Constants.updateShareStatus(mContext1, mInfo.mId, status);
                    }
                }
            }
            BluetoothOppUtility.cancelNotification(mContext);
            return status;
        }

        private void handleSendException(String exception) {
            Log.e(TAG, "Error when sending file: " + exception);
            // Update interrupted outbound content resolver entry when
            // error during transfer.
            Constants.updateShareStatus(mContext1, mInfo.mId,
                    BluetoothShare.STATUS_OBEX_DATA_ERROR);
            mCallbackHandler.removeMessages(BluetoothOppObexSession.MSG_CONNECT_TIMEOUT);
        }

        @Override
        public void interrupt() {
            super.interrupt();
            synchronized (this) {
                if (mWaitingForRemote) {
                    Log.v(TAG, "Interrupted when waitingForRemote");
                    try {
                        mTransport1.close();
                    } catch (IOException e) {
                        ContentProfileErrorReportUtils.report(
                                BluetoothProfile.OPP,
                                BluetoothProtoEnums.BLUETOOTH_OPP_OBEX_CLIENT_SESSION,
                                BluetoothStatsLog
                                        .BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                                16);
                        Log.e(TAG, "mTransport.close error");
                    }
                    Message msg = Message.obtain(mCallbackHandler);
                    msg.what = BluetoothOppObexSession.MSG_SHARE_INTERRUPTED;
                    if (mInfo != null) {
                        msg.obj = mInfo;
                    }
                    msg.sendToTarget();
                }
            }
        }
    }

    public static void applyRemoteDeviceQuirks(HeaderSet request, String address, String filename) {
        if (address == null) {
            return;
        }
        if (address.startsWith("00:04:48")) {
            // Poloroid Pogo
            // Rejects filenames with more than one '.'. Rename to '_'.
            // for example: 'a.b.jpg' -> 'a_b.jpg'
            //              'abc.jpg' NOT CHANGED
            char[] c = filename.toCharArray();
            boolean firstDot = true;
            boolean modified = false;
            for (int i = c.length - 1; i >= 0; i--) {
                if (c[i] == '.') {
                    if (!firstDot) {
                        modified = true;
                        c[i] = '_';
                    }
                    firstDot = false;
                }
            }

            if (modified) {
                String newFilename = new String(c);
                request.setHeader(HeaderSet.NAME, newFilename);
                Log.i(TAG, "Sending file \"" + filename + "\" as \"" + newFilename
                        + "\" to workaround Poloroid filename quirk");
            }
        }
    }

    @Override
    public void unblock() {
        // Not used for client case
    }

}
