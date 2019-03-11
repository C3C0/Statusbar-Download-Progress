/*
 * Copyright (C) 2019 Peter Gregus (C3C076@xda)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ceco.sbdp;

import java.io.File;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.util.Log;

public class Utils {

    static final boolean USE_DEVICE_PROTECTED_STORAGE = Build.VERSION.SDK_INT >= 24;
    private static final String TAG = "SBDP";
    private static final boolean DEBUG = false;

    @SuppressLint("NewApi")
    public static File getFilesDir(Context ctx) {
        if (USE_DEVICE_PROTECTED_STORAGE) {
            return ctx.isDeviceProtectedStorage() ?
                    ctx.getFilesDir() : ctx.createDeviceProtectedStorageContext().getFilesDir();
        }
        return ctx.getFilesDir();
    }

    @SuppressLint("NewApi")
    static File getDataDir(Context ctx) {
        if (USE_DEVICE_PROTECTED_STORAGE) {
            return ctx.isDeviceProtectedStorage() ?
                    ctx.getDataDir() : ctx.createDeviceProtectedStorageContext().getDataDir();
        }
        return new File(ctx.getFilesDir().getAbsolutePath() + "/..");
    }

    static void log(String message) {
        if (DEBUG) {
            Log.d(TAG, message);
        }
    } 
}
