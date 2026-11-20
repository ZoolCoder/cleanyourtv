package com.zoolcoder.tv.health;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the reminders again after the TV starts up or this app is updated, when there is anything to remind. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            HealthService.sync(context);
        }
    }
}
