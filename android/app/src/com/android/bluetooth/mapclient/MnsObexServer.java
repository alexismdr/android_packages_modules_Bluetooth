/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.bluetooth.mapclient;

import android.util.Log;

import com.android.bluetooth.ObexAppParameters;
import com.android.internal.annotations.VisibleForTesting;
import com.android.obex.HeaderSet;
import com.android.obex.Operation;
import com.android.obex.ResponseCodes;
import com.android.obex.ServerRequestHandler;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Arrays;

class MnsObexServer extends ServerRequestHandler {
    private static final String TAG = MnsObexServer.class.getSimpleName();

    @VisibleForTesting
    static final byte[] MNS_TARGET = new byte[]{
            (byte) 0xbb,
            0x58,
            0x2b,
            0x41,
            0x42,
            0x0c,
            0x11,
            (byte) 0xdb,
            (byte) 0xb0,
            (byte) 0xde,
            0x08,
            0x00,
            0x20,
            0x0c,
            (byte) 0x9a,
            0x66
    };

    @VisibleForTesting
    static final String TYPE = "x-bt/MAP-event-report";

    private final WeakReference<MceStateMachine> mStateMachineReference;

    MnsObexServer(MceStateMachine stateMachine) {
        super();
        mStateMachineReference = new WeakReference<>(stateMachine);
    }

    @Override
    public int onConnect(final HeaderSet request, HeaderSet reply) {
        Log.v(TAG, "onConnect");

        try {
            byte[] uuid = (byte[]) request.getHeader(HeaderSet.TARGET);
            if (!Arrays.equals(uuid, MNS_TARGET)) {
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
        } catch (IOException e) {
            // this should never happen since getHeader won't throw exception it
            // declares to throw
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        reply.setHeader(HeaderSet.WHO, MNS_TARGET);
        return ResponseCodes.OBEX_HTTP_OK;
    }

    @Override
    public void onDisconnect(final HeaderSet request, HeaderSet reply) {
        Log.v(TAG, "onDisconnect");
        MceStateMachine currentStateMachine = mStateMachineReference.get();
        if (currentStateMachine != null) {
            currentStateMachine.disconnect();
        }
    }

    @Override
    public int onGet(final Operation op) {
        Log.v(TAG, "onGet");
        return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
    }

    @Override
    public int onPut(final Operation op) {
        Log.v(TAG, "onPut");

        try {
            HeaderSet headerset;
            headerset = op.getReceivedHeader();

            String type = (String) headerset.getHeader(HeaderSet.TYPE);
            ObexAppParameters oap = ObexAppParameters.fromHeaderSet(headerset);
            if (!TYPE.equals(type) || !oap.exists(Request.OAP_TAGID_MAS_INSTANCE_ID)) {
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }

            EventReport ev = EventReport.fromStream(op.openDataInputStream());
            op.close();

            MceStateMachine currentStateMachine = mStateMachineReference.get();
            if (currentStateMachine != null) {
                currentStateMachine.receiveEvent(ev);
            }
        } catch (IOException e) {
            Log.e(TAG, "I/O exception when handling PUT request", e);
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        return ResponseCodes.OBEX_HTTP_OK;
    }

    @Override
    public int onAbort(final HeaderSet request, HeaderSet reply) {
        Log.v(TAG, "onAbort");
        return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
    }

    @Override
    public int onSetPath(final HeaderSet request, HeaderSet reply, final boolean backup,
            final boolean create) {
        Log.v(TAG, "onSetPath");
        return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
    }

    @Override
    public void onClose() {
        Log.v(TAG, "onClose");
    }
}
