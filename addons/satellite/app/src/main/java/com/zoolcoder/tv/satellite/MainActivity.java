package com.zoolcoder.tv.satellite;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * OK on the tile switches the TV to the satellite receiver's HDMI port and this activity ends; there is no
 * screen. The port comes from config.xml (empty: the first HDMI port with something connected). It reads the
 * TV's own input list; nothing is stored and nothing runs afterwards.
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TvInputManager tim = getSystemService(TvInputManager.class);
        List<Pick.Input> inputs = new ArrayList<>();
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                CharSequence custom = info.loadCustomLabel(this);
                inputs.add(new Pick.Input(info.getId(), info.getParentId(), String.valueOf(info.loadLabel(this)),
                        custom == null ? "" : custom.toString(), info.getType() == TvInputInfo.TYPE_HDMI,
                        tim.getInputState(info.getId()) != TvInputManager.INPUT_STATE_DISCONNECTED));
            }
        }
        String rule = getString(R.string.port);
        String id = Pick.choose(inputs, rule);
        if (id != null) open(id);
        else if (rule.trim().isEmpty()) say(R.string.no_inputs); // no HDMI port at all: a stick, or a TV with none
        else say(R.string.no_port);
        finish();
    }

    private void open(String inputId) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(inputId), "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String player = getString(R.string.live_tv_package);
        if (!player.isEmpty()) {
            try {
                startActivity(new Intent(view).setPackage(player));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        try {
            startActivity(view);
        } catch (ActivityNotFoundException | SecurityException e) {
            say(R.string.no_player);
        }
    }

    private void say(int text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
