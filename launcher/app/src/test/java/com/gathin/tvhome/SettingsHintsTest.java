package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SettingsHintsTest {
    @Test public void aSelectedOptionGetsOneCheck() {
        assertEquals("✓ 5", LauncherSettingsActivity.withCheck("5", true));
        assertEquals("✓ 5", LauncherSettingsActivity.withCheck("✓ 5", true));
    }

    @Test public void anOptionThatIsNotSelectedLosesTheCheck() {
        assertEquals("5", LauncherSettingsActivity.withCheck("✓ 5", false));
        assertEquals("70%", LauncherSettingsActivity.withCheck("70%", false));
    }
}
