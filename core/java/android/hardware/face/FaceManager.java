/**
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.face;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.MANAGE_FACE;
import static android.Manifest.permission.USE_BIOMETRIC;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.app.ActivityManager;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.CryptoObject;
import android.hardware.biometrics.IBiometricPromptReceiver;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.CancellationSignal.OnCancelListener;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * A class that coordinates access to the face authentication hardware.
 * @hide
 */
@SystemService(Context.FACE_SERVICE)
public class FaceManager implements BiometricFaceConstants {


    private static final String TAG = "FaceManager";
    private static final boolean DEBUG = true;
    private static final int MSG_ENROLL_RESULT = 100;
    private static final int MSG_ACQUIRED = 101;
    private static final int MSG_AUTHENTICATION_SUCCEEDED = 102;
    private static final int MSG_AUTHENTICATION_FAILED = 103;
    private static final int MSG_ERROR = 104;
    private static final int MSG_REMOVED = 105;

    private IFaceService mService;
    private final Context mContext;
    private IBinder mToken = new Binder();
    private BiometricAuthenticator.AuthenticationCallback mAuthenticationCallback;
    private EnrollmentCallback mEnrollmentCallback;
    private RemovalCallback mRemovalCallback;
    private CryptoObject mCryptoObject;
    private Face mRemovalFace;
    private Handler mHandler;
    private Executor mExecutor;

    private IFaceServiceReceiver mServiceReceiver = new IFaceServiceReceiver.Stub() {

        @Override // binder call
        public void onEnrollResult(long deviceId, int faceId, int remaining) {
            mHandler.obtainMessage(MSG_ENROLL_RESULT, remaining, 0,
                    new Face(null, faceId, deviceId)).sendToTarget();
        }

        @Override // binder call
        public void onAcquired(long deviceId, int acquireInfo, int vendorCode) {
            mHandler.obtainMessage(MSG_ACQUIRED, acquireInfo, vendorCode, deviceId).sendToTarget();
        }

        @Override // binder call
        public void onAuthenticationSucceeded(long deviceId, Face face) {
            mHandler.obtainMessage(MSG_AUTHENTICATION_SUCCEEDED, face).sendToTarget();
        }

        @Override // binder call
        public void onAuthenticationFailed(long deviceId) {
            mHandler.obtainMessage(MSG_AUTHENTICATION_FAILED).sendToTarget();
        }

        @Override // binder call
        public void onError(long deviceId, int error, int vendorCode) {
            mHandler.obtainMessage(MSG_ERROR, error, vendorCode, deviceId).sendToTarget();
        }

        @Override // binder call
        public void onRemoved(long deviceId, int faceId, int remaining) {
            mHandler.obtainMessage(MSG_REMOVED, remaining, 0,
                    new Face(null, faceId, deviceId)).sendToTarget();
        }
    };

    /**
     * @hide
     */
    public FaceManager(Context context, IFaceService service) {
        mContext = context;
        mService = service;
        if (mService == null) {
            Slog.v(TAG, "FaceAuthenticationManagerService was null");
        }
        mHandler = new MyHandler(context);
    }

    /**
     * Request authentication of a crypto object. This call operates the face recognition hardware
     * and starts capturing images. It terminates when
     * {@link AuthenticationCallback#onAuthenticationError(int, CharSequence)} or
     * {@link AuthenticationCallback#onAuthenticationSucceeded(AuthenticationResult)} is called, at
     * which point the object is no longer valid. The operation can be canceled by using the
     * provided cancel object.
     *
     * @param crypto   object associated with the call or null if none required.
     * @param cancel   an object that can be used to cancel authentication
     * @param flags    optional flags; should be 0
     * @param callback an object to receive authentication events
     * @param handler  an optional handler to handle callback events
     * @throws IllegalArgumentException if the crypto operation is not supported or is not backed
     *                                  by
     *                                  <a href="{@docRoot}training/articles/keystore.html">Android
     *                                  Keystore facility</a>.
     * @throws IllegalStateException    if the crypto primitive is not initialized.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC)
    public void authenticate(@Nullable CryptoObject crypto, @Nullable CancellationSignal cancel,
            int flags, @NonNull AuthenticationCallback callback, @Nullable Handler handler) {
        if (callback == null) {
            throw new IllegalArgumentException("Must supply an authentication callback");
        }

        if (cancel != null) {
            if (cancel.isCanceled()) {
                Log.w(TAG, "authentication already canceled");
                return;
            } else {
                cancel.setOnCancelListener(new OnAuthenticationCancelListener(crypto));
            }
        }

        if (mService != null) {
            try {
                useHandler(handler);
                mAuthenticationCallback = callback;
                mCryptoObject = crypto;
                long sessionId = crypto != null ? crypto.getOpId() : 0;
                mService.authenticate(mToken, sessionId, mServiceReceiver, flags,
                        mContext.getOpPackageName(), null /* bundle */, null /* receiver */);
            } catch (RemoteException e) {
                Log.w(TAG, "Remote exception while authenticating: ", e);
                if (callback != null) {
                    // Though this may not be a hardware issue, it will cause apps to give up or try
                    // again later.
                    callback.onAuthenticationError(FACE_ERROR_HW_UNAVAILABLE,
                            getErrorString(FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */));
                }
            }
        }
    }

    /**
     * Use the provided handler thread for events.
     */
    private void useHandler(Handler handler) {
        if (handler != null) {
            mHandler = new MyHandler(handler.getLooper());
        } else if (mHandler.getLooper() != mContext.getMainLooper()) {
            mHandler = new MyHandler(mContext.getMainLooper());
        }
    }

    /**
     * This method invokes the BiometricPrompt.
     */
    private void authenticateWithPrompt(@Nullable android.hardware.biometrics.CryptoObject crypto,
            @NonNull CancellationSignal cancel,
            @NonNull Bundle bundle,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull IBiometricPromptReceiver receiver,
            @NonNull BiometricAuthenticator.AuthenticationCallback callback) {
        mCryptoObject = crypto;
        if (cancel.isCanceled()) {
            Slog.w(TAG, "authentication already canceled");
            return;
        } else {
            cancel.setOnCancelListener(new OnAuthenticationCancelListener(crypto));
        }

        if (mService != null) {
            try {
                mExecutor = executor;
                mAuthenticationCallback = callback;
                final long sessionId = crypto != null ? crypto.getOpId() : 0;
                mService.authenticate(mToken, sessionId, mServiceReceiver,
                        0 /* flags */, mContext.getOpPackageName(), bundle, receiver);
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception while authenticating", e);
                mExecutor.execute(() -> {
                    callback.onAuthenticationError(FACE_ERROR_HW_UNAVAILABLE,
                            getErrorString(FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */));
                });
            }
        }
    }

    /**
     * Private method, see {@link BiometricPrompt#authenticate(CancellationSignal, Executor,
     * BiometricPrompt.AuthenticationCallback)}
     * @param cancel
     * @param executor
     * @param callback
     * @hide
     */
    public void authenticate(
            @NonNull CancellationSignal cancel,
            @NonNull Bundle bundle,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull IBiometricPromptReceiver receiver,
            @NonNull BiometricAuthenticator.AuthenticationCallback callback) {
        if (cancel == null) {
            throw new IllegalArgumentException("Must supply a cancellation signal");
        }
        if (bundle == null) {
            throw new IllegalArgumentException("Must supply a bundle");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must supply an executor");
        }
        if (receiver == null) {
            throw new IllegalArgumentException("Must supply a receiver");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Must supply a calback");
        }
        authenticateWithPrompt(null, cancel, bundle, executor, receiver, callback);
    }

    /**
     * Private method, see {@link BiometricPrompt#authenticate(BiometricPrompt.CryptoObject,
     * CancellationSignal, Executor, BiometricPrompt.AuthenticationCallback)}
     * @param crypto
     * @param cancel
     * @param executor
     * @param callback
     * @hide
     */
    public void authenticate(@NonNull android.hardware.biometrics.CryptoObject crypto,
            @NonNull CancellationSignal cancel,
            @NonNull Bundle bundle,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull IBiometricPromptReceiver receiver,
            @NonNull BiometricAuthenticator.AuthenticationCallback callback) {
        if (crypto == null) {
            throw new IllegalArgumentException("Must supply a crypto object");
        }
        if (cancel == null) {
            throw new IllegalArgumentException("Must supply a cancellation signal");
        }
        if (bundle == null) {
            throw new IllegalArgumentException("Must supply a bundle");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must supply an executor");
        }
        if (receiver == null) {
            throw new IllegalArgumentException("Must supply a receiver");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Must supply a callback");
        }
        authenticateWithPrompt(crypto, cancel, bundle, executor, receiver, callback);
    }

    /**
     * Request face authentication enrollment. This call operates the face authentication hardware
     * and starts capturing images. Progress will be indicated by callbacks to the
     * {@link EnrollmentCallback} object. It terminates when
     * {@link EnrollmentCallback#onEnrollmentError(int, CharSequence)} or
     * {@link EnrollmentCallback#onEnrollmentProgress(int) is called with remaining == 0, at
     * which point the object is no longer valid. The operation can be canceled by using the
     * provided cancel object.
     *
     * @param token    a unique token provided by a recent creation or verification of device
     *                 credentials (e.g. pin, pattern or password).
     * @param cancel   an object that can be used to cancel enrollment
     * @param flags    optional flags
     * @param userId   the user to whom this face will belong to
     * @param callback an object to receive enrollment events
     * @hide
     */
    @RequiresPermission(MANAGE_FACE)
    public void enroll(byte[] token, CancellationSignal cancel, int flags,
            int userId, EnrollmentCallback callback) {
        if (userId == UserHandle.USER_CURRENT) {
            userId = getCurrentUserId();
        }
        if (callback == null) {
            throw new IllegalArgumentException("Must supply an enrollment callback");
        }

        if (cancel != null) {
            if (cancel.isCanceled()) {
                Log.w(TAG, "enrollment already canceled");
                return;
            } else {
                cancel.setOnCancelListener(new OnEnrollCancelListener());
            }
        }

        if (mService != null) {
            try {
                mEnrollmentCallback = callback;
                mService.enroll(mToken, token, userId, mServiceReceiver, flags,
                        mContext.getOpPackageName());
            } catch (RemoteException e) {
                Log.w(TAG, "Remote exception in enroll: ", e);
                if (callback != null) {
                    // Though this may not be a hardware issue, it will cause apps to give up or try
                    // again later.
                    callback.onEnrollmentError(FACE_ERROR_HW_UNAVAILABLE,
                            getErrorString(FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */));
                }
            }
        }
    }

    /**
     * Requests a pre-enrollment auth token to tie enrollment to the confirmation of
     * existing device credentials (e.g. pin/pattern/password).
     *
     * @hide
     */
    @RequiresPermission(MANAGE_FACE)
    public long preEnroll() {
        long result = 0;
        if (mService != null) {
            try {
                result = mService.preEnroll(mToken);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return result;
    }

    /**
     * Finishes enrollment and cancels the current auth token.
     *
     * @hide
     */
    @RequiresPermission(MANAGE_FACE)
    public int postEnroll() {
        int result = 0;
        if (mService != null) {
            try {
                result = mService.postEnroll(mToken);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return result;
    }

    /**
     * Sets the active user. This is meant to be used to select the current profile for enrollment
     * to allow separate enrolled faces for a work profile
     *
     * @hide
     */
    @RequiresPermission(MANAGE_FACE)
    public void setActiveUser(int userId) {
        if (mService != null) {
            try {
                mService.setActiveUser(userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Remove given face template from face hardware and/or protected storage.
     *
     * @param face     the face item to remove
     * @param userId   the user who this face belongs to
     * @param callback an optional callback to verify that face templates have been
     *                 successfully removed. May be null if no callback is required.
     * @hide
     */
    @RequiresPermission(MANAGE_FACE)
    public void remove(Face face, int userId, RemovalCallback callback) {
        if (mService != null) {
            try {
                mRemovalCallback = callback;
                mRemovalFace = face;
                mService.remove(mToken, face.getBiometricId(), userId, mServiceReceiver);
            } catch (RemoteException e) {
                Log.w(TAG, "Remote exception in remove: ", e);
                if (callback != null) {
                    callback.onRemovalError(face, FACE_ERROR_HW_UNAVAILABLE,
                            getErrorString(FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */));
                }
            }
        }
    }

    /**
     * Obtain the enrolled face template.
     *
     * @return the current face item
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC)
    public List<Face> getEnrolledFaces(int userId) {
        if (mService != null) {
            try {
                return mService.getEnrolledFaces(userId, mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Obtain the enrolled face template.
     *
     * @return the current face item
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC)
    public List<Face> getEnrolledFaces() {
        return getEnrolledFaces(UserHandle.myUserId());
    }

    /**
     * Determine if there is a face enrolled.
     *
     * @return true if a face is enrolled, false otherwise
     */
    @RequiresPermission(USE_BIOMETRIC)
    public boolean hasEnrolledFaces() {
        if (mService != null) {
            try {
                return mService.hasEnrolledFaces(
                        UserHandle.myUserId(), mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * @hide
     */
    @RequiresPermission(allOf = {
            USE_BIOMETRIC,
            INTERACT_ACROSS_USERS})
    public boolean hasEnrolledFaces(int userId) {
        if (mService != null) {
            try {
                return mService.hasEnrolledFaces(userId, mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Determine if face authentication sensor hardware is present and functional.
     *
     * @return true if hardware is present and functional, false otherwise.
     */
    @RequiresPermission(USE_BIOMETRIC)
    public boolean isHardwareDetected() {
        if (mService != null) {
            try {
                long deviceId = 0; /* TODO: plumb hardware id to FPMS */
                return mService.isHardwareDetected(deviceId, mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Log.w(TAG, "isFaceHardwareDetected(): Service not connected!");
        }
        return false;
    }

    /**
     * Retrieves the authenticator token for binding keys to the lifecycle
     * of the calling user's face. Used only by internal clients.
     *
     * @hide
     */
    public long getAuthenticatorId() {
        if (mService != null) {
            try {
                return mService.getAuthenticatorId(mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Log.w(TAG, "getAuthenticatorId(): Service not connected!");
        }
        return 0;
    }

    /**
     * Reset the lockout timer when asked to do so by keyguard.
     *
     * @param token an opaque token returned by password confirmation.
     * @hide
     */
    public void resetTimeout(byte[] token) {
        if (mService != null) {
            try {
                mService.resetTimeout(token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Log.w(TAG, "resetTimeout(): Service not connected!");
        }
    }

    /**
     * @hide
     */
    public void addLockoutResetCallback(final LockoutResetCallback callback) {
        if (mService != null) {
            try {
                final PowerManager powerManager = mContext.getSystemService(PowerManager.class);
                mService.addLockoutResetCallback(
                        new IBiometricServiceLockoutResetCallback.Stub() {

                            @Override
                            public void onLockoutReset(long deviceId,
                                    IRemoteCallback serverCallback)
                                    throws RemoteException {
                                try {
                                    final PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                                            PowerManager.PARTIAL_WAKE_LOCK,
                                            "faceLockoutResetCallback");
                                    wakeLock.acquire();
                                    mHandler.post(() -> {
                                        try {
                                            callback.onLockoutReset();
                                        } finally {
                                            wakeLock.release();
                                        }
                                    });
                                } finally {
                                    serverCallback.sendResult(null /* data */);
                                }
                            }
                        });
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Log.w(TAG, "addLockoutResetCallback(): Service not connected!");
        }
    }

    private int getCurrentUserId() {
        try {
            return ActivityManager.getService().getCurrentUser().id;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void cancelEnrollment() {
        if (mService != null) {
            try {
                mService.cancelEnrollment(mToken);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    private void cancelAuthentication(CryptoObject cryptoObject) {
        if (mService != null) {
            try {
                mService.cancelAuthentication(mToken, mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    private String getErrorString(int errMsg, int vendorCode) {
        switch (errMsg) {
            case FACE_ERROR_HW_UNAVAILABLE:
                return mContext.getString(
                        com.android.internal.R.string.face_error_hw_not_available);
            case FACE_ERROR_UNABLE_TO_PROCESS:
                return mContext.getString(
                        com.android.internal.R.string.face_error_unable_to_process);
            case FACE_ERROR_TIMEOUT:
                return mContext.getString(com.android.internal.R.string.face_error_timeout);
            case FACE_ERROR_NO_SPACE:
                return mContext.getString(com.android.internal.R.string.face_error_no_space);
            case FACE_ERROR_CANCELED:
                return mContext.getString(com.android.internal.R.string.face_error_canceled);
            case FACE_ERROR_LOCKOUT:
                return mContext.getString(com.android.internal.R.string.face_error_lockout);
            case FACE_ERROR_LOCKOUT_PERMANENT:
                return mContext.getString(
                        com.android.internal.R.string.face_error_lockout_permanent);
            case FACE_ERROR_NOT_ENROLLED:
                return mContext.getString(com.android.internal.R.string.face_error_not_enrolled);
            case FACE_ERROR_HW_NOT_PRESENT:
                return mContext.getString(com.android.internal.R.string.face_error_hw_not_present);
            case FACE_ERROR_VENDOR: {
                String[] msgArray = mContext.getResources().getStringArray(
                        com.android.internal.R.array.face_error_vendor);
                if (vendorCode < msgArray.length) {
                    return msgArray[vendorCode];
                }
            }
        }
        Slog.w(TAG, "Invalid error message: " + errMsg + ", " + vendorCode);
        return null;
    }

    private String getAcquiredString(int acquireInfo, int vendorCode) {
        switch (acquireInfo) {
            case FACE_ACQUIRED_GOOD:
                return null;
            case FACE_ACQUIRED_INSUFFICIENT:
                return mContext.getString(R.string.face_acquired_insufficient);
            case FACE_ACQUIRED_TOO_BRIGHT:
                return mContext.getString(R.string.face_acquired_too_bright);
            case FACE_ACQUIRED_TOO_DARK:
                return mContext.getString(R.string.face_acquired_too_dark);
            case FACE_ACQUIRED_TOO_CLOSE:
                return mContext.getString(R.string.face_acquired_too_close);
            case FACE_ACQUIRED_TOO_FAR:
                return mContext.getString(R.string.face_acquired_too_far);
            case FACE_ACQUIRED_TOO_HIGH:
                return mContext.getString(R.string.face_acquired_too_high);
            case FACE_ACQUIRED_TOO_LOW:
                return mContext.getString(R.string.face_acquired_too_low);
            case FACE_ACQUIRED_TOO_RIGHT:
                return mContext.getString(R.string.face_acquired_too_right);
            case FACE_ACQUIRED_TOO_LEFT:
                return mContext.getString(R.string.face_acquired_too_left);
            case FACE_ACQUIRED_POOR_GAZE:
                return mContext.getString(R.string.face_acquired_poor_gaze);
            case FACE_ACQUIRED_NOT_DETECTED:
                return mContext.getString(R.string.face_acquired_not_detected);
            case FACE_ACQUIRED_VENDOR: {
                String[] msgArray = mContext.getResources().getStringArray(
                        R.array.face_acquired_vendor);
                if (vendorCode < msgArray.length) {
                    return msgArray[vendorCode];
                }
            }
        }
        Slog.w(TAG, "Invalid acquired message: " + acquireInfo + ", " + vendorCode);
        return null;
    }

    /**
     * Container for callback data from {@link FaceManager#authenticate(CryptoObject,
     * CancellationSignal, int, AuthenticationCallback, Handler)}.
     */
    public static class AuthenticationResult {
        private Face mFace;
        private CryptoObject mCryptoObject;
        private int mUserId;

        /**
         * Authentication result
         *
         * @param crypto the crypto object
         * @param face   the recognized face data, if allowed.
         * @hide
         */
        public AuthenticationResult(CryptoObject crypto, Face face, int userId) {
            mCryptoObject = crypto;
            mFace = face;
            mUserId = userId;
        }

        /**
         * Obtain the crypto object associated with this transaction
         *
         * @return crypto object provided to {@link FaceManager#authenticate
         * (CryptoObject,
         * CancellationSignal, int, AuthenticationCallback, Handler)}.
         */
        public CryptoObject getCryptoObject() {
            return mCryptoObject;
        }

        /**
         * Obtain the Face associated with this operation. Applications are strongly
         * discouraged from associating specific faces with specific applications or operations.
         *
         * @hide
         */
        public Face getFace() {
            return mFace;
        }

        /**
         * Obtain the userId for which this face was authenticated.
         *
         * @hide
         */
        public int getUserId() {
            return mUserId;
        }
    }

    /**
     * Callback structure provided to {@link FaceManager#authenticate(CryptoObject,
     * CancellationSignal, int, AuthenticationCallback, Handler)}. Users of {@link
     * FaceManager#authenticate(CryptoObject, CancellationSignal,
     * int, AuthenticationCallback, Handler) } must provide an implementation of this for listening
     * to face events.
     */
    public abstract static class AuthenticationCallback
            extends BiometricAuthenticator.AuthenticationCallback {

        /**
         * Called when an unrecoverable error has been encountered and the operation is complete.
         * No further callbacks will be made on this object.
         *
         * @param errorCode An integer identifying the error message
         * @param errString A human-readable error string that can be shown in UI
         */
        public void onAuthenticationError(int errorCode, CharSequence errString) {
        }

        /**
         * Called when a recoverable error has been encountered during authentication. The help
         * string is provided to give the user guidance for what went wrong, such as
         * "Sensor dirty, please clean it."
         *
         * @param helpCode   An integer identifying the error message
         * @param helpString A human-readable string that can be shown in UI
         */
        public void onAuthenticationHelp(int helpCode, CharSequence helpString) {
        }

        /**
         * Called when a face is recognized.
         *
         * @param result An object containing authentication-related data
         */
        public void onAuthenticationSucceeded(AuthenticationResult result) {
        }

        /**
         * Called when a face is detected but not recognized.
         */
        public void onAuthenticationFailed() {
        }

        /**
         * Called when a face image has been acquired, but wasn't processed yet.
         *
         * @param acquireInfo one of FACE_ACQUIRED_* constants
         * @hide
         */
        public void onAuthenticationAcquired(int acquireInfo) {
        }

        /**
         * @hide
         * @param result
         */
        @Override
        public void onAuthenticationSucceeded(BiometricAuthenticator.AuthenticationResult result) {
            onAuthenticationSucceeded(new AuthenticationResult(
                    result.getCryptoObject(),
                    (Face) result.getId(), result.getUserId()));
        }

    }

    /**
     * Callback structure provided to {@link FaceManager#enroll(long,
     * EnrollmentCallback, CancellationSignal, int). Users of {@link #FaceAuthenticationManager()}
     * must provide an implementation of this to {@link FaceManager#enroll(long,
     * CancellationSignal, int, EnrollmentCallback) for listening to face enrollment events.
     *
     * @hide
     */
    public abstract static class EnrollmentCallback {

        /**
         * Called when an unrecoverable error has been encountered and the operation is complete.
         * No further callbacks will be made on this object.
         *
         * @param errMsgId  An integer identifying the error message
         * @param errString A human-readable error string that can be shown in UI
         */
        public void onEnrollmentError(int errMsgId, CharSequence errString) {
        }

        /**
         * Called when a recoverable error has been encountered during enrollment. The help
         * string is provided to give the user guidance for what went wrong, such as
         * "Image too dark, uncover light source" or what they need to do next, such as
         * "Rotate face up / down."
         *
         * @param helpMsgId  An integer identifying the error message
         * @param helpString A human-readable string that can be shown in UI
         */
        public void onEnrollmentHelp(int helpMsgId, CharSequence helpString) {
        }

        /**
         * Called as each enrollment step progresses. Enrollment is considered complete when
         * remaining reaches 0. This function will not be called if enrollment fails. See
         * {@link EnrollmentCallback#onEnrollmentError(int, CharSequence)}
         *
         * @param remaining The number of remaining steps
         */
        public void onEnrollmentProgress(int remaining) {
        }
    }

    /**
     * Callback structure provided to {@link #remove}. Users of {@link FaceManager}
     * may
     * optionally provide an implementation of this to
     * {@link #remove(Face, int, RemovalCallback)} for listening to face template
     * removal events.
     *
     * @hide
     */
    public abstract static class RemovalCallback {

        /**
         * Called when the given face can't be removed.
         *
         * @param face      The face that the call attempted to remove
         * @param errMsgId  An associated error message id
         * @param errString An error message indicating why the face id can't be removed
         */
        public void onRemovalError(Face face, int errMsgId, CharSequence errString) {
        }

        /**
         * Called when a given face is successfully removed.
         *
         * @param face The face template that was removed.
         */
        public void onRemovalSucceeded(Face face, int remaining) {
        }
    }

    /**
     * @hide
     */
    public abstract static class LockoutResetCallback {

        /**
         * Called when lockout period expired and clients are allowed to listen for face
         * authentication
         * again.
         */
        public void onLockoutReset() {
        }
    }

    private class OnEnrollCancelListener implements OnCancelListener {
        @Override
        public void onCancel() {
            cancelEnrollment();
        }
    }

    private class OnAuthenticationCancelListener implements OnCancelListener {
        private CryptoObject mCrypto;

        OnAuthenticationCancelListener(CryptoObject crypto) {
            mCrypto = crypto;
        }

        @Override
        public void onCancel() {
            cancelAuthentication(mCrypto);
        }
    }

    private class MyHandler extends Handler {
        private MyHandler(Context context) {
            super(context.getMainLooper());
        }

        private MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_ENROLL_RESULT:
                    sendEnrollResult((Face) msg.obj, msg.arg1 /* remaining */);
                    break;
                case MSG_ACQUIRED:
                    sendAcquiredResult((Long) msg.obj /* deviceId */, msg.arg1 /* acquire info */,
                            msg.arg2 /* vendorCode */);
                    break;
                case MSG_AUTHENTICATION_SUCCEEDED:
                    sendAuthenticatedSucceeded((Face) msg.obj, msg.arg1 /* userId */);
                    break;
                case MSG_AUTHENTICATION_FAILED:
                    sendAuthenticatedFailed();
                    break;
                case MSG_ERROR:
                    sendErrorResult((Long) msg.obj /* deviceId */, msg.arg1 /* errMsgId */,
                            msg.arg2 /* vendorCode */);
                    break;
                case MSG_REMOVED:
                    sendRemovedResult((Face) msg.obj, msg.arg1 /* remaining */);
                    break;
            }
        }
    };

    private void sendRemovedResult(Face face, int remaining) {
        if (mRemovalCallback == null) {
            return;
        }
        if (face == null) {
            Log.e(TAG, "Received MSG_REMOVED, but face is null");
            return;
        }
        mRemovalCallback.onRemovalSucceeded(face, remaining);
    }

    private void sendErrorResult(long deviceId, int errMsgId, int vendorCode) {
        // emulate HAL 2.1 behavior and send real errMsgId
        final int clientErrMsgId = errMsgId == FACE_ERROR_VENDOR
                ? (vendorCode + FACE_ERROR_VENDOR_BASE) : errMsgId;
        if (mEnrollmentCallback != null) {
            mEnrollmentCallback.onEnrollmentError(clientErrMsgId,
                    getErrorString(errMsgId, vendorCode));
        } else if (mAuthenticationCallback != null) {
            mAuthenticationCallback.onAuthenticationError(clientErrMsgId,
                    getErrorString(errMsgId, vendorCode));
        } else if (mRemovalCallback != null) {
            mRemovalCallback.onRemovalError(mRemovalFace, clientErrMsgId,
                    getErrorString(errMsgId, vendorCode));
        }
    }

    private void sendEnrollResult(Face face, int remaining) {
        if (mEnrollmentCallback != null) {
            mEnrollmentCallback.onEnrollmentProgress(remaining);
        }
    }

    private void sendAuthenticatedSucceeded(Face face, int userId) {
        if (mAuthenticationCallback != null) {
            final BiometricAuthenticator.AuthenticationResult result =
                    new BiometricAuthenticator.AuthenticationResult(mCryptoObject, face, userId);
            mAuthenticationCallback.onAuthenticationSucceeded(result);
        }
    }

    private void sendAuthenticatedFailed() {
        if (mAuthenticationCallback != null) {
            mAuthenticationCallback.onAuthenticationFailed();
        }
    }

    private void sendAcquiredResult(long deviceId, int acquireInfo, int vendorCode) {
        if (mAuthenticationCallback != null) {
            mAuthenticationCallback.onAuthenticationAcquired(acquireInfo);
        }
        final String msg = getAcquiredString(acquireInfo, vendorCode);
        if (msg == null) {
            return;
        }
        final int clientInfo = acquireInfo == FACE_ACQUIRED_VENDOR
                ? (vendorCode + FACE_ACQUIRED_VENDOR_BASE) : acquireInfo;
        if (mEnrollmentCallback != null) {
            mEnrollmentCallback.onEnrollmentHelp(clientInfo, msg);
        } else if (mAuthenticationCallback != null) {
            mAuthenticationCallback.onAuthenticationHelp(clientInfo, msg);
        }
    }
}
