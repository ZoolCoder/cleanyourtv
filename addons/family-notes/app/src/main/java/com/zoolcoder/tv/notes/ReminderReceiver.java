package com.zoolcoder.tv.notes;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Lists a reminder that Family Calendar just popped up. Only apps signed with this app's key may send it
 * (the receiver's signature permission); it never pops anything up itself, since the calendar already did.
 */
public final class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Reminders.ACTION.equals(intent.getAction())) return;
        Note note = Reminders.note(intent.getStringExtra(Reminders.TITLE), intent.getStringExtra(Reminders.WHO),
                intent.getStringExtra(Reminders.WHEN), context.getString(R.string.reminder_from), System.currentTimeMillis());
        if (note != null) new Store(context).add(note);
    }
}
