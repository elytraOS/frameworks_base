/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.util.elytra;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.R;

public class ElytraUtils {

    public static final String INTENT_SCREENSHOT = "action_handler_screenshot";
    public static final String INTENT_REGION_SCREENSHOT = "action_handler_region_screenshot";

    public static boolean deviceHasCompass(Context ctx) {
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        return sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
                && sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null;
    }

    // Method to turn off the screen
    public static void switchScreenOff(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm!= null) {
            pm.goToSleep(SystemClock.uptimeMillis());
        }
    }

    public static void takeScreenshot(boolean full) {
        IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
        try {
            wm.sendCustomAction(new Intent(full? INTENT_SCREENSHOT : INTENT_REGION_SCREENSHOT));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
}
