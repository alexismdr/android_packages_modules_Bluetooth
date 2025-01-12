/*
 * Copyright (C) 2008 The Android Open Source Project
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

import static android.Manifest.permission.BLUETOOTH_CONNECT;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.PhoneLookup;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.util.DevicePolicyUtils;
import com.android.bluetooth.util.GsmAlphabet;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;

/**
 * Helper for managing phonebook presentation over AT commands
 */
public class AtPhonebook {
    private static final String TAG = "BluetoothAtPhonebook";

    /** The projection to use when querying the call log database in response
     *  to AT+CPBR for the MC, RC, and DC phone books (missed, received, and
     *   dialed calls respectively)
     */
    private static final String[] CALLS_PROJECTION = new String[]{
            Calls._ID, Calls.NUMBER, Calls.NUMBER_PRESENTATION
    };

    /** The projection to use when querying the contacts database in response
     *   to AT+CPBR for the ME phonebook (saved phone numbers).
     */
    private static final String[] PHONES_PROJECTION = new String[]{
            Phone._ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE
    };

    /** Android supports as many phonebook entries as the flash can hold, but
     *  BT periphals don't. Limit the number we'll report. */
    private static final int MAX_PHONEBOOK_SIZE = 16384;

    private static final String OUTGOING_CALL_WHERE = Calls.TYPE + "=" + Calls.OUTGOING_TYPE;
    private static final String INCOMING_CALL_WHERE = Calls.TYPE + "=" + Calls.INCOMING_TYPE;
    private static final String MISSED_CALL_WHERE = Calls.TYPE + "=" + Calls.MISSED_TYPE;

    @VisibleForTesting
    class PhonebookResult {
        public Cursor cursor; // result set of last query
        public int numberColumn;
        public int numberPresentationColumn;
        public int typeColumn;
        public int nameColumn;
    }

    private Context mContext;
    private ContentResolver mContentResolver;
    private HeadsetNativeInterface mNativeInterface;
    @VisibleForTesting
    String mCurrentPhonebook;
    @VisibleForTesting
    String mCharacterSet = "UTF-8";

    @VisibleForTesting
    int mCpbrIndex1, mCpbrIndex2;
    private boolean mCheckingAccessPermission;

    // package and class name to which we send intent to check phone book access permission
    private final String mPairingPackage;

    @VisibleForTesting
    final HashMap<String, PhonebookResult> mPhonebooks =
            new HashMap<String, PhonebookResult>(4);

    static final int TYPE_UNKNOWN = -1;
    static final int TYPE_READ = 0;
    static final int TYPE_SET = 1;
    static final int TYPE_TEST = 2;

    public AtPhonebook(Context context, HeadsetNativeInterface nativeInterface) {
        mContext = context;
        mPairingPackage = SystemProperties.get(
            Utils.PAIRING_UI_PROPERTY,
            context.getString(R.string.pairing_ui_package));
        mContentResolver = context.getContentResolver();
        mNativeInterface = nativeInterface;
        mPhonebooks.put("DC", new PhonebookResult());  // dialled calls
        mPhonebooks.put("RC", new PhonebookResult());  // received calls
        mPhonebooks.put("MC", new PhonebookResult());  // missed calls
        mPhonebooks.put("ME", new PhonebookResult());  // mobile phonebook
        mCurrentPhonebook = "ME";  // default to mobile phonebook
        mCpbrIndex1 = mCpbrIndex2 = -1;
    }

    public void cleanup() {
        mPhonebooks.clear();
    }

    /** Returns the last dialled number, or null if no numbers have been called */
    public String getLastDialledNumber() {
        String[] projection = {Calls.NUMBER};
        Bundle queryArgs = new Bundle();
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                Calls.TYPE + "=" + Calls.OUTGOING_TYPE);
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, Calls.DEFAULT_SORT_ORDER);
        queryArgs.putInt(ContentResolver.QUERY_ARG_LIMIT, 1);

        Cursor cursor = mContentResolver.query(Calls.CONTENT_URI, projection, queryArgs, null);
        if (cursor == null) {
            Log.w(TAG, "getLastDialledNumber, cursor is null");
            return null;
        }

        if (cursor.getCount() < 1) {
            cursor.close();
            Log.w(TAG, "getLastDialledNumber, cursor.getCount is 0");
            return null;
        }
        cursor.moveToNext();
        int column = cursor.getColumnIndexOrThrow(Calls.NUMBER);
        String number = cursor.getString(column);
        cursor.close();
        return number;
    }

    public boolean getCheckingAccessPermission() {
        return mCheckingAccessPermission;
    }

    public void setCheckingAccessPermission(boolean checkingAccessPermission) {
        mCheckingAccessPermission = checkingAccessPermission;
    }

    public void setCpbrIndex(int cpbrIndex) {
        mCpbrIndex1 = mCpbrIndex2 = cpbrIndex;
    }

    public void handleCscsCommand(String atString, int type, BluetoothDevice device) {
        Log.d(TAG, "handleCscsCommand - atString = " + atString);
        // Select Character Set
        int atCommandResult = HeadsetHalConstants.AT_RESPONSE_ERROR;
        int atCommandErrorCode = -1;
        String atCommandResponse = null;
        switch (type) {
            case TYPE_READ: // Read
                Log.d(TAG, "handleCscsCommand - Read Command");
                atCommandResponse = "+CSCS: \"" + mCharacterSet + "\"";
                atCommandResult = HeadsetHalConstants.AT_RESPONSE_OK;
                break;
            case TYPE_TEST: // Test
                Log.d(TAG, "handleCscsCommand - Test Command");
                atCommandResponse = ("+CSCS: (\"UTF-8\",\"IRA\",\"GSM\")");
                atCommandResult = HeadsetHalConstants.AT_RESPONSE_OK;
                break;
            case TYPE_SET: // Set
                Log.d(TAG, "handleCscsCommand - Set Command");
                String[] args = atString.split("=");
                if (args.length < 2 || args[1] == null) {
                    mNativeInterface.atResponseCode(device, atCommandResult, atCommandErrorCode);
                    break;
                }
                String characterSet = ((atString.split("="))[1]);
                characterSet = characterSet.replace("\"", "");
                if (characterSet.equals("GSM") || characterSet.equals("IRA") || characterSet.equals(
                        "UTF-8") || characterSet.equals("UTF8")) {
                    mCharacterSet = characterSet;
                    atCommandResult = HeadsetHalConstants.AT_RESPONSE_OK;
                } else {
                    atCommandErrorCode = BluetoothCmeError.OPERATION_NOT_SUPPORTED;
                }
                break;
            case TYPE_UNKNOWN:
            default:
                Log.d(TAG, "handleCscsCommand - Invalid chars");
                atCommandErrorCode = BluetoothCmeError.TEXT_HAS_INVALID_CHARS;
        }
        if (atCommandResponse != null) {
            mNativeInterface.atResponseString(device, atCommandResponse);
        }
        mNativeInterface.atResponseCode(device, atCommandResult, atCommandErrorCode);
    }

    public void handleCpbsCommand(String atString, int type, BluetoothDevice device) {
        // Select PhoneBook memory Storage
        Log.d(TAG, "handleCpbsCommand - atString = " + atString);
        int atCommandResult = HeadsetHalConstants.AT_RESPONSE_ERROR;
        int atCommandErrorCode = -1;
        String atCommandResponse = null;
        switch (type) {
            case TYPE_READ: // Read
                Log.d(TAG, "handleCpbsCommand - read command");
                // Return current size and max size
                if ("SM".equals(mCurrentPhonebook)) {
                    atCommandResponse = "+CPBS: \"SM\",0," + getMaxPhoneBookSize(0);
                    atCommandResult = HeadsetHalConstants.AT_RESPONSE_OK;
                    break;
                }
                PhonebookResult pbr = getPhonebookResult(mCurrentPhonebook, true);
                if (pbr == null) {
                    atCommandErrorCode = BluetoothCmeError.OPERATION_NOT_SUPPORTED;
                    break;
                }
                int size = pbr.cursor.getCount();
                atCommandResponse =
                        "+CPBS: \"" + mCurrentPhonebook + "\"," + size + "," + getMaxPhoneBookSize(
                                size);
                pbr.cursor.close();
                pbr.cursor = null;
                atCommandResult = HeadsetHalConstants.AT_RESPONSE_OK;
                break;
            case TYPE_TEST: // Test
                Log.d(TAG, "handleCpbsCommand - test command");
                atCommandResponse = ("+CPBS: (\"ME\",\"SM\",\"DC\",\"RC\",\"MC\")");
                atCommandResult = HeadsetHalConstants.AT_RESPONSE_OK;
                break;
            case TYPE_SET: // Set
                Log.d(TAG, "handleCpbsCommand - set command");
                String[] args = atString.split("=");
                // Select phonebook memory
                if (args.length < 2 || args[1] == null) {
                    atCommandErrorCode = BluetoothCmeError.OPERATION_NOT_SUPPORTED;
                    break;
                }
                String pb = args[1].trim();
                while (pb.endsWith("\"")) {
                    pb = pb.substring(0, pb.length() - 1);
                }
                while (pb.startsWith("\"")) {
                    pb = pb.substring(1, pb.length());
                }
                if (getPhonebookResult(pb, false) == null && !"SM".equals(pb)) {
                    Log.d(TAG, "Dont know phonebook: '" + pb + "'");
                    atCommandErrorCode = BluetoothCmeError.OPERATION_NOT_ALLOWED;
                    break;
                }
                mCurrentPhonebook = pb;
                atCommandResult = HeadsetHalConstants.AT_RESPONSE_OK;
                break;
            case TYPE_UNKNOWN:
            default:
                Log.d(TAG, "handleCpbsCommand - invalid chars");
                atCommandErrorCode = BluetoothCmeError.TEXT_HAS_INVALID_CHARS;
        }
        if (atCommandResponse != null) {
            mNativeInterface.atResponseString(device, atCommandResponse);
        }
        mNativeInterface.atResponseCode(device, atCommandResult, atCommandErrorCode);
    }

    void handleCpbrCommand(String atString, int type, BluetoothDevice remoteDevice) {
        Log.d(TAG, "handleCpbrCommand - atString = " + atString);
        int atCommandResult = HeadsetHalConstants.AT_RESPONSE_ERROR;
        int atCommandErrorCode = -1;
        String atCommandResponse = null;
        switch (type) {
            case TYPE_TEST: // Test
                /* Ideally we should return the maximum range of valid index's
                 * for the selected phone book, but this causes problems for the
                 * Parrot CK3300. So instead send just the range of currently
                 * valid index's.
                 */
                Log.d(TAG, "handleCpbrCommand - test command");
                int size;
                if ("SM".equals(mCurrentPhonebook)) {
                    size = 0;
                } else {
                    PhonebookResult pbr = getPhonebookResult(mCurrentPhonebook, true); //false);
                    if (pbr == null) {
                        atCommandErrorCode = BluetoothCmeError.OPERATION_NOT_ALLOWED;
                        mNativeInterface.atResponseCode(remoteDevice, atCommandResult,
                                atCommandErrorCode);
                        break;
                    }
                    size = pbr.cursor.getCount();
                    Log.d(TAG, "handleCpbrCommand - size = " + size);
                    pbr.cursor.close();
                    pbr.cursor = null;
                }
                if (size == 0) {
                    /* Sending "+CPBR: (1-0)" can confused some carkits, send "1-1" * instead */
                    size = 1;
                }
                atCommandResponse = "+CPBR: (1-" + size + "),30,30";
                atCommandResult = HeadsetHalConstants.AT_RESPONSE_OK;
                mNativeInterface.atResponseString(remoteDevice, atCommandResponse);
                mNativeInterface.atResponseCode(remoteDevice, atCommandResult, atCommandErrorCode);
                break;
            // Read PhoneBook Entries
            case TYPE_READ:
            case TYPE_SET: // Set & read
                // Phone Book Read Request
                // AT+CPBR=<index1>[,<index2>]
                Log.d(TAG, "handleCpbrCommand - set/read command");
                if (mCpbrIndex1 != -1) {
                   /* handling a CPBR at the moment, reject this CPBR command */
                    atCommandErrorCode = BluetoothCmeError.OPERATION_NOT_ALLOWED;
                    mNativeInterface.atResponseCode(remoteDevice, atCommandResult,
                            atCommandErrorCode);
                    break;
                }
                // Parse indexes
                int index1;
                int index2;
                if ((atString.split("=")).length < 2) {
                    mNativeInterface.atResponseCode(remoteDevice, atCommandResult,
                            atCommandErrorCode);
                    break;
                }
                String atCommand = (atString.split("="))[1];
                String[] indices = atCommand.split(",");
                //replace AT command separator ';' from the index if any
                for (int i = 0; i < indices.length; i++) {
                    indices[i] = indices[i].replace(';', ' ').trim();
                }
                try {
                    index1 = Integer.parseInt(indices[0]);
                    if (indices.length == 1) {
                        index2 = index1;
                    } else {
                        index2 = Integer.parseInt(indices[1]);
                    }
                } catch (Exception e) {
                    Log.d(TAG, "handleCpbrCommand - exception - invalid chars: " + e.toString());
                    atCommandErrorCode = BluetoothCmeError.TEXT_HAS_INVALID_CHARS;
                    mNativeInterface.atResponseCode(remoteDevice, atCommandResult,
                            atCommandErrorCode);
                    break;
                }
                mCpbrIndex1 = index1;
                mCpbrIndex2 = index2;
                mCheckingAccessPermission = true;

                int permission = checkAccessPermission(remoteDevice);
                if (permission == BluetoothDevice.ACCESS_ALLOWED) {
                    mCheckingAccessPermission = false;
                    atCommandResult = processCpbrCommand(remoteDevice);
                    mCpbrIndex1 = mCpbrIndex2 = -1;
                    mNativeInterface.atResponseCode(remoteDevice, atCommandResult,
                            atCommandErrorCode);
                    break;
                } else if (permission == BluetoothDevice.ACCESS_REJECTED) {
                    mCheckingAccessPermission = false;
                    mCpbrIndex1 = mCpbrIndex2 = -1;
                    mNativeInterface.atResponseCode(remoteDevice,
                            HeadsetHalConstants.AT_RESPONSE_ERROR, BluetoothCmeError.AG_FAILURE);
                }
                // If checkAccessPermission(remoteDevice) has returned
                // BluetoothDevice.ACCESS_UNKNOWN, we will continue the process in
                // HeadsetStateMachine.handleAccessPermissionResult(Intent) once HeadsetService
                // receives BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY from Settings app.
                break;
            case TYPE_UNKNOWN:
            default:
                Log.d(TAG, "handleCpbrCommand - invalid chars");
                atCommandErrorCode = BluetoothCmeError.TEXT_HAS_INVALID_CHARS;
                mNativeInterface.atResponseCode(remoteDevice, atCommandResult, atCommandErrorCode);
        }
    }

    /** Get the most recent result for the given phone book,
     *  with the cursor ready to go.
     *  If force then re-query that phonebook
     *  Returns null if the cursor is not ready
     */
    @VisibleForTesting
    synchronized PhonebookResult getPhonebookResult(String pb, boolean force) {
        if (pb == null) {
            return null;
        }
        PhonebookResult pbr = mPhonebooks.get(pb);
        if (pbr == null) {
            pbr = new PhonebookResult();
        }
        if (force || pbr.cursor == null) {
            if (!queryPhonebook(pb, pbr)) {
                return null;
            }
        }

        return pbr;
    }

    private synchronized boolean queryPhonebook(String pb, PhonebookResult pbr) {
        String where;
        boolean ancillaryPhonebook = true;

        if (pb.equals("ME")) {
            ancillaryPhonebook = false;
            where = null;
        } else if (pb.equals("DC")) {
            where = OUTGOING_CALL_WHERE;
        } else if (pb.equals("RC")) {
            where = INCOMING_CALL_WHERE;
        } else if (pb.equals("MC")) {
            where = MISSED_CALL_WHERE;
        } else {
            return false;
        }

        if (pbr.cursor != null) {
            pbr.cursor.close();
            pbr.cursor = null;
        }

        if (ancillaryPhonebook) {
            Bundle queryArgs = new Bundle();
            queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where);
            queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, Calls.DEFAULT_SORT_ORDER);
            queryArgs.putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_PHONEBOOK_SIZE);
            pbr.cursor = BluetoothMethodProxy.getInstance().contentResolverQuery(mContentResolver,
                    Calls.CONTENT_URI, CALLS_PROJECTION, queryArgs, null);

            if (pbr.cursor == null) {
                return false;
            }
            pbr.numberColumn = pbr.cursor.getColumnIndexOrThrow(Calls.NUMBER);
            pbr.numberPresentationColumn =
                    pbr.cursor.getColumnIndexOrThrow(Calls.NUMBER_PRESENTATION);
            pbr.typeColumn = -1;
            pbr.nameColumn = -1;
        } else {
            Bundle queryArgs = new Bundle();
            queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where);
            queryArgs.putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_PHONEBOOK_SIZE);
            final Uri phoneContentUri = DevicePolicyUtils.getEnterprisePhoneUri(mContext);
            pbr.cursor = BluetoothMethodProxy.getInstance().contentResolverQuery(mContentResolver,
                    phoneContentUri, PHONES_PROJECTION, queryArgs, null);

            if (pbr.cursor == null) {
                return false;
            }

            pbr.numberColumn = pbr.cursor.getColumnIndex(Phone.NUMBER);
            pbr.numberPresentationColumn = -1;
            pbr.typeColumn = pbr.cursor.getColumnIndex(Phone.TYPE);
            pbr.nameColumn = pbr.cursor.getColumnIndex(Phone.DISPLAY_NAME);
        }
        Log.i(TAG, "Refreshed phonebook " + pb + " with " + pbr.cursor.getCount() + " results");
        return true;
    }

    synchronized void resetAtState() {
        mCharacterSet = "UTF-8";
        mCpbrIndex1 = mCpbrIndex2 = -1;
        mCheckingAccessPermission = false;
    }

    @VisibleForTesting
    synchronized int getMaxPhoneBookSize(int currSize) {
        // some car kits ignore the current size and request max phone book
        // size entries. Thus, it takes a long time to transfer all the
        // entries. Use a heuristic to calculate the max phone book size
        // considering future expansion.
        // maxSize = currSize + currSize / 2 rounded up to nearest power of 2
        // If currSize < 100, use 100 as the currSize

        int maxSize = (currSize < 100) ? 100 : currSize;
        maxSize += maxSize / 2;
        return roundUpToPowerOfTwo(maxSize);
    }

    private int roundUpToPowerOfTwo(int x) {
        x |= x >> 1;
        x |= x >> 2;
        x |= x >> 4;
        x |= x >> 8;
        x |= x >> 16;
        return x + 1;
    }

    // process CPBR command after permission check
    /*package*/ int processCpbrCommand(BluetoothDevice device) {
        Log.d(TAG, "processCpbrCommand");
        int atCommandResult = HeadsetHalConstants.AT_RESPONSE_ERROR;
        String atCommandResponse = null;
        String record;

        // Shortcut SM phonebook
        if ("SM".equals(mCurrentPhonebook)) {
            atCommandResult = HeadsetHalConstants.AT_RESPONSE_OK;
            return atCommandResult;
        }

        // Check phonebook
        PhonebookResult pbr = getPhonebookResult(mCurrentPhonebook, true); //false);
        if (pbr == null) {
            Log.e(TAG, "pbr is null");
            return atCommandResult;
        }

        // More sanity checks
        // Send OK instead of ERROR if these checks fail.
        // When we send error, certain kits like BMW disconnect the
        // Handsfree connection.
        if (pbr.cursor.getCount() == 0 || mCpbrIndex1 <= 0 || mCpbrIndex2 < mCpbrIndex1
                || mCpbrIndex1 > pbr.cursor.getCount()) {
            atCommandResult = HeadsetHalConstants.AT_RESPONSE_OK;
            Log.e(TAG, "Invalid request or no results, returning");
            return atCommandResult;
        }

        if (mCpbrIndex2 > pbr.cursor.getCount()) {
            Log.w(TAG, "max index requested is greater than number of records"
                    + " available, resetting it");
            mCpbrIndex2 = pbr.cursor.getCount();
        }
        // Process
        atCommandResult = HeadsetHalConstants.AT_RESPONSE_OK;
        pbr.cursor.moveToPosition(mCpbrIndex1 - 1);
        Log.d(TAG, "mCpbrIndex1 = " + mCpbrIndex1 + " and mCpbrIndex2 = " + mCpbrIndex2);
        for (int index = mCpbrIndex1; index <= mCpbrIndex2; index++) {
            String number = pbr.cursor.getString(pbr.numberColumn);
            String name = null;
            int type = -1;
            if (pbr.nameColumn == -1 && number != null && number.length() > 0) {
                // try caller id lookup
                // TODO: This code is horribly inefficient. I saw it
                // take 7 seconds to process 100 missed calls.
                Cursor c =
                        BluetoothMethodProxy.getInstance()
                                .contentResolverQuery(
                                        mContentResolver,
                                        Uri.withAppendedPath(
                                                PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI,
                                                Uri.encode(number)),
                                        new String[] {PhoneLookup.DISPLAY_NAME, PhoneLookup.TYPE},
                                        null,
                                        null,
                                        null);
                if (c != null) {
                    if (c.moveToFirst()) {
                        name = c.getString(0);
                        type = c.getInt(1);
                    }
                    c.close();
                }
                if (name == null) {
                    Log.d(TAG, "Caller ID lookup failed for " + number);
                }

            } else if (pbr.nameColumn != -1) {
                name = pbr.cursor.getString(pbr.nameColumn);
            } else {
                Log.d(TAG, "processCpbrCommand: empty name and number");
            }
            if (name == null) {
                name = "";
            }
            name = name.trim();
            if (name.length() > 28) {
                name = name.substring(0, 28);
            }

            if (pbr.typeColumn != -1) {
                type = pbr.cursor.getInt(pbr.typeColumn);
                name = name + "/" + getPhoneType(type);
            }

            if (number == null) {
                number = "";
            }
            int regionType = PhoneNumberUtils.toaFromString(number);

            number = number.trim();
            number = PhoneNumberUtils.stripSeparators(number);
            if (number.length() > 30) {
                number = number.substring(0, 30);
            }
            int numberPresentation = Calls.PRESENTATION_ALLOWED;
            if (pbr.numberPresentationColumn != -1) {
                numberPresentation = pbr.cursor.getInt(pbr.numberPresentationColumn);
            }
            if (numberPresentation != Calls.PRESENTATION_ALLOWED) {
                number = "";
                // TODO: there are 3 types of numbers should have resource
                // strings for: unknown, private, and payphone
                name = mContext.getString(R.string.unknownNumber);
            }

            // TODO(): Handle IRA commands. It's basically
            // a 7 bit ASCII character set.
            if (!name.isEmpty() && mCharacterSet.equals("GSM")) {
                byte[] nameByte = GsmAlphabet.stringToGsm8BitPacked(name);
                if (nameByte == null) {
                    name = mContext.getString(R.string.unknownNumber);
                } else {
                    name = new String(nameByte);
                }
            }

            record = "+CPBR: " + index + ",\"" + number + "\"," + regionType + ",\"" + name + "\"";
            record = record + "\r\n\r\n";
            atCommandResponse = record;
            mNativeInterface.atResponseString(device, atCommandResponse);
            if (!pbr.cursor.moveToNext()) {
                break;
            }
        }
        if (pbr.cursor != null) {
            pbr.cursor.close();
            pbr.cursor = null;
        }
        return atCommandResult;
    }

    /**
     * Checks if the remote device has permission to read our phone book. If the return value is
     * {@link BluetoothDevice#ACCESS_UNKNOWN}, it means this method has sent an Intent to Settings
     * application to ask user preference.
     *
     * @return {@link BluetoothDevice#ACCESS_UNKNOWN}, {@link BluetoothDevice#ACCESS_ALLOWED} or
     *     {@link BluetoothDevice#ACCESS_REJECTED}.
     */
    @VisibleForTesting
    int checkAccessPermission(BluetoothDevice remoteDevice) {
        Log.d(TAG, "checkAccessPermission");
        int permission = remoteDevice.getPhonebookAccessPermission();

        if (permission == BluetoothDevice.ACCESS_UNKNOWN) {
            Log.d(TAG, "checkAccessPermission - ACTION_CONNECTION_ACCESS_REQUEST");
            Intent intent = new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_REQUEST);
            intent.setPackage(mPairingPackage);
            intent.putExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                    BluetoothDevice.REQUEST_TYPE_PHONEBOOK_ACCESS);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, remoteDevice);
            // Leave EXTRA_PACKAGE_NAME and EXTRA_CLASS_NAME field empty.
            // BluetoothHandsfree's broadcast receiver is anonymous, cannot be targeted.
            mContext.sendOrderedBroadcast(
                    intent,
                    BLUETOOTH_CONNECT,
                    Utils.getTempBroadcastOptions().toBundle(),
                    null,
                    null,
                    Activity.RESULT_OK,
                    null,
                    null);
        }

        return permission;
    }

    @VisibleForTesting
    static String getPhoneType(int type) {
        switch (type) {
            case Phone.TYPE_HOME:
                return "H";
            case Phone.TYPE_MOBILE:
                return "M";
            case Phone.TYPE_WORK:
                return "W";
            case Phone.TYPE_FAX_HOME:
            case Phone.TYPE_FAX_WORK:
                return "F";
            case Phone.TYPE_OTHER:
            case Phone.TYPE_CUSTOM:
            default:
                return "O";
        }
    }
}
