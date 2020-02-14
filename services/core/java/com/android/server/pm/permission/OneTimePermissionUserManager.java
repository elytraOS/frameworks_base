/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.pm.permission;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.permission.PermissionControllerManager;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

/**
 * Class that handles one-time permissions for a user
 */
public class OneTimePermissionUserManager {

    private static final String LOG_TAG = OneTimePermissionUserManager.class.getSimpleName();

    private static final boolean DEBUG = true;

    private final @NonNull Context mContext;
    private final @NonNull ActivityManager mActivityManager;
    private final @NonNull AlarmManager mAlarmManager;
    private final @NonNull PermissionControllerManager mPermissionControllerManager;

    private final Object mLock = new Object();

    /** Maps the uid to the PackageInactivityListener */
    @GuardedBy("mLock")
    private final SparseArray<PackageInactivityListener> mListeners = new SparseArray<>();

    OneTimePermissionUserManager(@NonNull Context context) {
        mContext = context;
        mActivityManager = context.getSystemService(ActivityManager.class);
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mPermissionControllerManager = context.getSystemService(PermissionControllerManager.class);
    }

    /**
     * Starts a one-time permission session for a given package. A one-time permission session is
     * ended if app becomes inactive. Inactivity is defined as the package's uid importance level
     * staying > importanceToResetTimer for timeoutMillis milliseconds. If the package's uid
     * importance level goes <= importanceToResetTimer then the timer is reset and doesn't start
     * until going > importanceToResetTimer.
     * <p>
     * When this timeoutMillis is reached if the importance level is <= importanceToKeepSessionAlive
     * then the session is extended until either the importance goes above
     * importanceToKeepSessionAlive which will end the session or <= importanceToResetTimer which
     * will continue the session and reset the timer.
     * </p>
     * <p>
     * Importance levels are defined in {@link android.app.ActivityManager.RunningAppProcessInfo}.
     * </p>
     * <p>
     * Once the session ends PermissionControllerService#onNotifyOneTimePermissionSessionTimeout
     * is invoked.
     * </p>
     * <p>
     * Note that if there is currently an active session for a package a new one isn't created and
     * the existing one isn't changed.
     * </p>
     * @param packageName The package to start a one-time permission session for
     * @param timeoutMillis Number of milliseconds for an app to be in an inactive state
     * @param importanceToResetTimer The least important level to uid must be to reset the timer
     * @param importanceToKeepSessionAlive The least important level the uid must be to keep the
     *                                    session alive
     *
     * @hide
     */
    void startPackageOneTimeSession(@NonNull String packageName, long timeoutMillis,
            int importanceToResetTimer, int importanceToKeepSessionAlive) {
        int uid;
        try {
            uid = mContext.getPackageManager().getPackageUid(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "Unknown package name " + packageName, e);
            return;
        }

        synchronized (mLock) {
            PackageInactivityListener listener = mListeners.get(uid);
            if (listener == null) {
                listener = new PackageInactivityListener(uid, packageName, timeoutMillis,
                        importanceToResetTimer, importanceToKeepSessionAlive);
                mListeners.put(uid, listener);
            }
        }
    }

    /**
     * Stops the one-time permission session for the package. The callback to the end of session is
     * not invoked. If there is no one-time session for the package then nothing happens.
     *
     * @param packageName Package to stop the one-time permission session for
     */
    void stopPackageOneTimeSession(@NonNull String packageName) {
        int uid;
        try {
            uid = mContext.getPackageManager().getPackageUid(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "Unknown package name " + packageName, e);
            return;
        }

        synchronized (mLock) {
            PackageInactivityListener listener = mListeners.get(uid);
            if (listener != null) {
                mListeners.remove(uid);
                listener.cancel();
            }
        }
    }

    /**
     * A class which watches a package for inactivity and notifies the permission controller when
     * the package becomes inactive
     */
    private class PackageInactivityListener implements AlarmManager.OnAlarmListener {

        private static final long TIMER_INACTIVE = -1;

        private final int mUid;
        private final @NonNull String mPackageName;
        private final long mTimeout;
        private final int mImportanceToResetTimer;
        private final int mImportanceToKeepSessionAlive;

        private boolean mIsAlarmSet;
        private boolean mIsFinished;

        private long mTimerStart = TIMER_INACTIVE;

        private final ActivityManager.OnUidImportanceListener mStartTimerListener;
        private final ActivityManager.OnUidImportanceListener mSessionKillableListener;
        private final ActivityManager.OnUidImportanceListener mGoneListener;

        private final Object mInnerLock = new Object();

        private PackageInactivityListener(int uid, @NonNull String packageName, long timeout,
                int importanceToResetTimer, int importanceToKeepSessionAlive) {

            if (DEBUG) {
                Log.d(LOG_TAG,
                        "Start tracking " + packageName + ". uid=" + uid + " timeout=" + timeout
                                + " importanceToResetTimer=" + importanceToResetTimer
                                + " importanceToKeepSessionAlive=" + importanceToKeepSessionAlive);
            }

            mUid = uid;
            mPackageName = packageName;
            mTimeout = timeout;
            mImportanceToResetTimer = importanceToResetTimer;
            mImportanceToKeepSessionAlive = importanceToKeepSessionAlive;

            mStartTimerListener =
                    (changingUid, importance) -> onImportanceChanged(changingUid, importance);
            mSessionKillableListener =
                    (changingUid, importance) -> onImportanceChanged(changingUid, importance);
            mGoneListener =
                    (changingUid, importance) -> onImportanceChanged(changingUid, importance);

            mActivityManager.addOnUidImportanceListener(mStartTimerListener,
                    importanceToResetTimer);
            mActivityManager.addOnUidImportanceListener(mSessionKillableListener,
                    importanceToKeepSessionAlive);
            mActivityManager.addOnUidImportanceListener(mGoneListener, IMPORTANCE_CACHED);

            onImportanceChanged(mUid, mActivityManager.getPackageImportance(packageName));
        }

        private void onImportanceChanged(int uid, int importance) {
            if (uid != mUid) {
                return;
            }


            if (DEBUG) {
                Log.d(LOG_TAG, "Importance changed for " + mPackageName + " (" + mUid + ")."
                        + " importance=" + importance);
            }
            synchronized (mInnerLock) {
                if (importance > IMPORTANCE_CACHED) {
                    onPackageInactiveLocked();
                    return;
                }
                if (importance > mImportanceToResetTimer) {
                    if (mTimerStart == TIMER_INACTIVE) {
                        mTimerStart = System.currentTimeMillis();
                    }
                } else {
                    mTimerStart = TIMER_INACTIVE;
                }
                if (importance > mImportanceToKeepSessionAlive) {
                    setAlarmLocked();
                } else {
                    cancelAlarmLocked();
                }
            }
        }

        /**
         * Stop watching the package for inactivity
         */
        private void cancel() {
            synchronized (mInnerLock) {
                mIsFinished = true;
                cancelAlarmLocked();
                mActivityManager.removeOnUidImportanceListener(mStartTimerListener);
                mActivityManager.removeOnUidImportanceListener(mSessionKillableListener);
                mActivityManager.removeOnUidImportanceListener(mGoneListener);
            }
        }

        /**
         * Set the alarm which will callback when the package is inactive
         */
        @GuardedBy("mInnerLock")
        private void setAlarmLocked() {
            if (mIsAlarmSet) {
                return;
            }

            long revokeTime = mTimerStart + mTimeout;
            if (revokeTime > System.currentTimeMillis()) {
                mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, revokeTime, LOG_TAG, this,
                        mContext.getMainThreadHandler());
                mIsAlarmSet = true;
            } else {
                mIsAlarmSet = true;
                onAlarm();
            }
        }

        /**
         * Cancel the alarm
         */
        @GuardedBy("mInnerLock")
        private void cancelAlarmLocked() {
            if (mIsAlarmSet) {
                mAlarmManager.cancel(this);
                mIsAlarmSet = false;
            }
        }

        /**
         * Called when the package is considered inactive. This is the end of the session
         */
        @GuardedBy("mInnerLock")
        private void onPackageInactiveLocked() {
            if (mIsFinished) {
                return;
            }
            mIsFinished = true;
            cancelAlarmLocked();
            mContext.getMainThreadHandler().post(
                    () -> {
                        if (DEBUG) {
                            Log.d(LOG_TAG, "One time session expired for "
                                    + mPackageName + " (" + mUid + ").");
                        }

                        mPermissionControllerManager.notifyOneTimePermissionSessionTimeout(
                                mPackageName);
                    });
            mActivityManager.removeOnUidImportanceListener(mStartTimerListener);
            mActivityManager.removeOnUidImportanceListener(mSessionKillableListener);
            mActivityManager.removeOnUidImportanceListener(mGoneListener);
            synchronized (mLock) {
                mListeners.remove(mUid);
            }
        }

        @Override
        public void onAlarm() {
            synchronized (mInnerLock) {
                if (!mIsAlarmSet) {
                    return;
                }
                mIsAlarmSet = false;
                onPackageInactiveLocked();
            }
        }
    }
}
