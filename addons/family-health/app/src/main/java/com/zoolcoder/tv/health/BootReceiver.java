package com.zoolcoder.tv.health;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the reminders again after the TV starts up or this app is updated, when there is anything to remind.
 * A start-up also switches the phone page off: the clock may be wrong until the network sets it, so the page's
 * own switch-off time cannot be trusted then.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)) new Phone(context).setEnabled(false);
            HealthService.sync(context);
        }
    }
}
