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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Trace;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.android.keyguard.R;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.tuner.TunerService;

import cyanogenmod.providers.CMSettings;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Field;

/**
 * Encapsulates all logic for the status bar window state management.
 */
public class StatusBarWindowManager implements RemoteInputController.Callback,
        TunerService.Tunable, KeyguardMonitor.Callback {

    private static final String ACCELEROMETER_ROTATION =
            "system:" + Settings.System.ACCELEROMETER_ROTATION;
    private static final String LOCKSCREEN_ROTATION =
            "cmsystem:" + CMSettings.System.LOCKSCREEN_ROTATION;
    private static final String LOCK_SCREEN_BLUR_ENABLED =
            "cmsecure:" + CMSettings.Secure.LOCK_SCREEN_BLUR_ENABLED;

    private static final int TYPE_LAYER_MULTIPLIER = 10000; // Refer to WindowManagerService.TYPE_LAYER_MULTIPLIER
    private static final int TYPE_LAYER_OFFSET = 1000;      // Refer to WindowManagerService.TYPE_LAYER_OFFSET
    private static final int STATUS_BAR_LAYER = 16 * TYPE_LAYER_MULTIPLIER + TYPE_LAYER_OFFSET;

    private static final String TAG = "StatusBarWindowManager";

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final IActivityManager mActivityManager;
    private View mStatusBarView;
    private WindowManager.LayoutParams mLp;
    private WindowManager.LayoutParams mLpChanged;
    private boolean mHasTopUi;
    private boolean mHasTopUiChanged;
    private int mBarHeight;
    private boolean mKeyguardScreenRotation;
    private final float mScreenBrightnessDoze;
    private final State mCurrentState = new State();

    private BlurLayer mBlurLayer;
    private boolean mShowingMedia;
    private boolean mKeyguardBlurEnabled;

    public StatusBarWindowManager(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mActivityManager = ActivityManagerNative.getDefault();
        mKeyguardScreenRotation = shouldEnableKeyguardScreenRotation();
        mScreenBrightnessDoze = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessDoze) / 255f;
    }

    private boolean shouldEnableKeyguardScreenRotation() {
        Resources res = mContext.getResources();
        boolean enableAccelerometerRotation = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 1) != 0;
        boolean enableLockScreenRotation = CMSettings.System.getInt(mContext.getContentResolver(),
                CMSettings.System.LOCKSCREEN_ROTATION, 0) != 0;
        return SystemProperties.getBoolean("lockscreen.rot_override", false)
                || (res.getBoolean(R.bool.config_enableLockScreenRotation)
                && (enableLockScreenRotation && enableAccelerometerRotation));
    }

    /**
     * Adds the status bar view to the window manager.
     *
     * @param statusBarView The view to add.
     * @param barHeight The height of the status bar in collapsed state.
     */
    public void add(View statusBarView, int barHeight) {

        // Now that the status bar window encompasses the sliding panel and its
        // translucent backdrop, the entire thing is made TRANSLUCENT and is
        // hardware-accelerated.
        mLp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                barHeight,
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                PixelFormat.TRANSLUCENT);
        mLp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        mLp.gravity = Gravity.TOP;
        mLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        mLp.setTitle("StatusBar");
        mLp.packageName = mContext.getPackageName();
        mStatusBarView = statusBarView;
        mBarHeight = barHeight;
        mWindowManager.addView(mStatusBarView, mLp);
        mLpChanged = new WindowManager.LayoutParams();
        mLpChanged.copyFrom(mLp);

        SettingsObserver observer = new SettingsObserver(new Handler());
        observer.observe(mContext);

        final boolean isBlurSupported = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_uiBlurEnabled);
        if (isBlurSupported) {
            final Point xy = getDisplayDimensions(mWindowManager);
            mBlurLayer = new BlurLayer(xy.x, xy.y, STATUS_BAR_LAYER - 2, "KeyGuard");
            TunerService.get(mContext).addTunable(this, LOCK_SCREEN_BLUR_ENABLED);
        }

        TunerService.get(mContext).addTunable(this,
                ACCELEROMETER_ROTATION,
                LOCKSCREEN_ROTATION);
    }

    private void applyKeyguardFlags(State state) {
        if (state.keyguardShowing) {
            mLpChanged.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
            if (!mKeyguardBlurEnabled || mShowingMedia) {
                mLpChanged.flags |= WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
            }
        } else {
            mLpChanged.privateFlags &= ~WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
            if (mKeyguardBlurEnabled && mBlurLayer != null) {
                mBlurLayer.hide();
            }
        }

        if (state.keyguardShowing && !state.backdropShowing) {
            mLpChanged.flags |= WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
        } else {
            mLpChanged.flags &= ~WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
        }
    }

    private void adjustScreenOrientation(State state) {
        if (state.isKeyguardShowingAndNotOccluded()) {
            if (mKeyguardScreenRotation) {
                mLpChanged.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_USER;
            } else {
                mLpChanged.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
            }
        } else {
            mLpChanged.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
    }

    private void applyFocusableFlag(State state) {
        boolean panelFocusable = state.statusBarFocusable && state.panelExpanded;
        if (state.keyguardShowing && state.keyguardNeedsInput && state.bouncerShowing
                || BaseStatusBar.ENABLE_REMOTE_INPUT && state.remoteInputActive) {
            mLpChanged.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            mLpChanged.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        } else if (state.isKeyguardShowingAndNotOccluded() || panelFocusable) {
            mLpChanged.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            mLpChanged.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        } else {
            mLpChanged.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            mLpChanged.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        }

        mLpChanged.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
    }

    private void applyHeight(State state) {
        boolean expanded = isExpanded(state);
        if (expanded) {
            mLpChanged.height = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            mLpChanged.height = mBarHeight;
        }
    }

    private boolean isExpanded(State state) {
        return !state.forceCollapsed && (state.isKeyguardShowingAndNotOccluded()
                || state.panelVisible || state.keyguardFadingAway || state.bouncerShowing
                || state.headsUpShowing);
    }

    private void applyFitsSystemWindows(State state) {
        boolean fitsSystemWindows = !state.isKeyguardShowingAndNotOccluded();
        if (mStatusBarView.getFitsSystemWindows() != fitsSystemWindows) {
            mStatusBarView.setFitsSystemWindows(fitsSystemWindows);
            mStatusBarView.requestApplyInsets();
        }
    }

    private void applyUserActivityTimeout(State state) {
        if (state.isKeyguardShowingAndNotOccluded()
                && state.statusBarState == StatusBarState.KEYGUARD
                && !state.qsExpanded) {
            mLpChanged.userActivityTimeout = KeyguardViewMediator.AWAKE_INTERVAL_DEFAULT_MS;
        } else {
            mLpChanged.userActivityTimeout = -1;
        }
    }

    private void applyInputFeatures(State state) {
        if (state.isKeyguardShowingAndNotOccluded()
                && state.statusBarState == StatusBarState.KEYGUARD
                && !state.qsExpanded && !state.forceUserActivity) {
            mLpChanged.inputFeatures |=
                    WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;
        } else {
            mLpChanged.inputFeatures &=
                    ~WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;
        }
    }

    private void apply(State state) {
        applyKeyguardFlags(state);
        applyForceStatusBarVisibleFlag(state);
        applyFocusableFlag(state);
        adjustScreenOrientation(state);
        applyHeight(state);
        applyUserActivityTimeout(state);
        applyInputFeatures(state);
        applyFitsSystemWindows(state);
        applyModalFlag(state);
        applyBrightness(state);
        applyHasTopUi(state);
        if (mLp.copyFrom(mLpChanged) != 0) {
            mWindowManager.updateViewLayout(mStatusBarView, mLp);
        }
        if (mHasTopUi != mHasTopUiChanged) {
            try {
                mActivityManager.setHasTopUi(mHasTopUiChanged);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to call setHasTopUi", e);
            }
            mHasTopUi = mHasTopUiChanged;
        }
    }

    private void applyForceStatusBarVisibleFlag(State state) {
        if (state.forceStatusBarVisible) {
            mLpChanged.privateFlags |= WindowManager
                    .LayoutParams.PRIVATE_FLAG_FORCE_STATUS_BAR_VISIBLE_TRANSPARENT;
        } else {
            mLpChanged.privateFlags &= ~WindowManager
                    .LayoutParams.PRIVATE_FLAG_FORCE_STATUS_BAR_VISIBLE_TRANSPARENT;
        }
    }

    private void applyModalFlag(State state) {
        if (state.headsUpShowing) {
            mLpChanged.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        } else {
            mLpChanged.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        }
    }

    private void applyBrightness(State state) {
        if (state.forceDozeBrightness) {
            mLpChanged.screenBrightness = mScreenBrightnessDoze;
        } else {
            mLpChanged.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
    }

    private void applyHasTopUi(State state) {
        mHasTopUiChanged = isExpanded(state);
    }

    public void setKeyguardShowing(boolean showing) {
        mCurrentState.keyguardShowing = showing;
        apply(mCurrentState);
    }

    public void setKeyguardOccluded(boolean occluded) {
        final boolean oldOccluded = mCurrentState.keyguardOccluded;
        mCurrentState.keyguardOccluded = occluded;
        if (oldOccluded != occluded) {
            showKeyguardBlur();
        }
        apply(mCurrentState);
    }

    public void setKeyguardNeedsInput(boolean needsInput) {
        mCurrentState.keyguardNeedsInput = needsInput;
        apply(mCurrentState);
    }

    public void setPanelVisible(boolean visible) {
        mCurrentState.panelVisible = visible;
        mCurrentState.statusBarFocusable = visible;
        apply(mCurrentState);
    }

    public void setStatusBarFocusable(boolean focusable) {
        mCurrentState.statusBarFocusable = focusable;
        apply(mCurrentState);
    }

    public void setBouncerShowing(boolean showing) {
        mCurrentState.bouncerShowing = showing;
        apply(mCurrentState);
    }

    public void setBackdropShowing(boolean showing) {
        mCurrentState.backdropShowing = showing;
        apply(mCurrentState);
    }

    public void setKeyguardFadingAway(boolean keyguardFadingAway) {
        mCurrentState.keyguardFadingAway = keyguardFadingAway;
        apply(mCurrentState);
    }

    public void setQsExpanded(boolean expanded) {
        mCurrentState.qsExpanded = expanded;
        apply(mCurrentState);
    }

    public void setForceUserActivity(boolean forceUserActivity) {
        mCurrentState.forceUserActivity = forceUserActivity;
        apply(mCurrentState);
    }

    public void setHeadsUpShowing(boolean showing) {
        mCurrentState.headsUpShowing = showing;
        apply(mCurrentState);
    }

    /**
     * @param state The {@link StatusBarState} of the status bar.
     */
    public void setStatusBarState(int state) {
        mCurrentState.statusBarState = state;
        apply(mCurrentState);
    }

    public void setForceStatusBarVisible(boolean forceStatusBarVisible) {
        mCurrentState.forceStatusBarVisible = forceStatusBarVisible;
        apply(mCurrentState);
    }

    /**
     * Force the window to be collapsed, even if it should theoretically be expanded.
     * Used for when a heads-up comes in but we still need to wait for the touchable regions to
     * be computed.
     */
    public void setForceWindowCollapsed(boolean force) {
        mCurrentState.forceCollapsed = force;
        apply(mCurrentState);
    }

    public void setPanelExpanded(boolean isExpanded) {
        mCurrentState.panelExpanded = isExpanded;
        apply(mCurrentState);
    }

    @Override
    public void onRemoteInputActive(boolean remoteInputActive) {
        mCurrentState.remoteInputActive = remoteInputActive;
        apply(mCurrentState);
    }

    /**
     * Set whether the screen brightness is forced to the value we use for doze mode by the status
     * bar window.
     */
    public void setForceDozeBrightness(boolean forceDozeBrightness) {
        mCurrentState.forceDozeBrightness = forceDozeBrightness;
        apply(mCurrentState);
    }

    public void setBarHeight(int barHeight) {
        mBarHeight = barHeight;
        apply(mCurrentState);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("StatusBarWindowManager state:");
        pw.println(mCurrentState);
    }

    public boolean isShowingWallpaper() {
        return !mCurrentState.backdropShowing;
    }

    void onConfigurationChanged() {
        if (mBlurLayer == null) {
            return;
        }

        final Point dimensions = getDisplayDimensions(mWindowManager);
        mBlurLayer.setSize(dimensions.x, dimensions.y);
    }

    void setShowingMedia(boolean showingMedia) {
        mShowingMedia = showingMedia;
        showKeyguardBlur();
    }

    private void showKeyguardBlur() {
        if (mBlurLayer == null) {
            return;
        }

        final boolean shouldBlur = mKeyguardBlurEnabled && !mShowingMedia &&
                mCurrentState.keyguardShowing && !mCurrentState.keyguardOccluded;
        if (shouldBlur) {
            mBlurLayer.show();
        } else {
            mBlurLayer.hide();
        }
    }

    private Point getDisplayDimensions(WindowManager wm) {
        final Point xy = new Point();
        wm.getDefaultDisplay().getRealSize(xy);
        return xy;
    }

    private static class State {
        boolean keyguardShowing;
        boolean keyguardOccluded;
        boolean keyguardNeedsInput;
        boolean panelVisible;
        boolean panelExpanded;
        boolean statusBarFocusable;
        boolean bouncerShowing;
        boolean keyguardFadingAway;
        boolean qsExpanded;
        boolean headsUpShowing;
        boolean forceStatusBarVisible;
        boolean forceCollapsed;
        boolean forceDozeBrightness;
        boolean forceUserActivity;
        boolean backdropShowing;

        /**
         * The {@link BaseStatusBar} state from the status bar.
         */
        int statusBarState;

        boolean remoteInputActive;

        private boolean isKeyguardShowingAndNotOccluded() {
            return keyguardShowing && !keyguardOccluded;
        }

        @Override
        public String toString() {
            StringBuilder result = new StringBuilder();
            String newLine = "\n";
            result.append("Window State {");
            result.append(newLine);

            Field[] fields = this.getClass().getDeclaredFields();

            // Print field names paired with their values
            for (Field field : fields) {
                result.append("  ");
                try {
                    result.append(field.getName());
                    result.append(": ");
                    //requires access to private field:
                    result.append(field.get(this));
                } catch (IllegalAccessException ex) {
                }
                result.append(newLine);
            }
            result.append("}");

            return result.toString();
        }
    }

    private class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        public void observe(Context context) {
            context.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
                    false,
                    this);
            context.getContentResolver().registerContentObserver(
                    CMSettings.System.getUriFor(CMSettings.System.LOCKSCREEN_ROTATION),
                    false,
                    this);
        }

        public void unobserve(Context context) {
            context.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            mKeyguardScreenRotation = shouldEnableKeyguardScreenRotation();
            // update the state
            apply(mCurrentState);
        }
			
    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case ACCELEROMETER_ROTATION:
            case LOCKSCREEN_ROTATION:
                mKeyguardScreenRotation = shouldEnableKeyguardScreenRotation();
                break;
            case LOCK_SCREEN_BLUR_ENABLED:
                mKeyguardBlurEnabled = newValue == null || Integer.parseInt(newValue) == 1;
                break;
            default:
                return;
        }
    }

    @Override
    public void onKeyguardChanged() {
        showKeyguardBlur();
    }
}
