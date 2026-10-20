package com.zafri.atvlauncher;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Lets the Power pill sleep the TV and open the system power menu. Apps can't do either directly
 * (DEVICE_POWER / REBOOT are system-only permissions); accessibility services are allowed to.
 * It listens to no events and reads no screen content.
 */
public class PowerService extends AccessibilityService {
    private static PowerService instance;

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    /** Standby, same as the remote's power button. False if the service isn't enabled. */
    static boolean sleep() {
        return instance != null && instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    /** The system's power menu (restart / power off). False if the service isn't enabled. */
    static boolean powerMenu() {
        return instance != null && instance.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }
}
