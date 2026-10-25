package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

public abstract class XCallback {
    public static abstract class Param {
        public Object[] callbacks;
    }
}
