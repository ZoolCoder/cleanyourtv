package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;

import org.junit.Test;

public class WallpaperCaptionsTest {

    @Test
    public void readsFilenameTabName() {
        Map<String, String> names = WallpaperCaptions.parse(Arrays.asList(
                "001.jpg\tRed Spider Nebula · Hubble",
                "002.jpg\t  Falcon 9 at sunset  "));
        assertEquals("Red Spider Nebula · Hubble", names.get("001.jpg"));
        assertEquals("Falcon 9 at sunset", names.get("002.jpg"));
    }

    @Test
    public void skipsCommentsBlankAndMalformedLines() {
        Map<String, String> names = WallpaperCaptions.parse(Arrays.asList(
                "# captions for the collection", "", "   ", "no-tab-here.jpg Dusk", "\tno file", "003.jpg\t", null));
        assertTrue(names.isEmpty());
    }

    @Test
    public void cutsVeryLongNames() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 30; i++) longName.append("word ");
        Map<String, String> names = WallpaperCaptions.parse(Arrays.asList("004.jpg\t" + longName));
        assertTrue(names.get("004.jpg").length() <= WallpaperCaptions.MAX_LENGTH);
    }

    @Test
    public void laterLineWinsForTheSameFile() {
        Map<String, String> names = WallpaperCaptions.parse(Arrays.asList("005.jpg\tOld", "005.jpg\tNew"));
        assertEquals("New", names.get("005.jpg"));
        assertFalse(names.containsKey("006.jpg"));
    }

    @Test
    public void missingFolderHasNoNames() {
        assertTrue(WallpaperCaptions.read(null).isEmpty());
        assertTrue(WallpaperCaptions.read(null, WallpaperCaptions.CREDITS_FILE_NAME).isEmpty());
    }

    @Test
    public void creditsComeFromTheirOwnFile() throws Exception {
        File folder = Files.createTempDirectory("credits").toFile();
        try {
            Files.write(new File(folder, WallpaperCaptions.CREDITS_FILE_NAME).toPath(),
                    Arrays.asList("# authors", "007.jpg\tPhoto: Ada, CC BY 4.0"), StandardCharsets.UTF_8);
            Map<String, String> credits = WallpaperCaptions.read(folder, WallpaperCaptions.CREDITS_FILE_NAME);
            assertEquals("Photo: Ada, CC BY 4.0", credits.get("007.jpg"));
            assertTrue(WallpaperCaptions.read(folder).isEmpty());
        } finally {
            for (File f : folder.listFiles()) f.delete();
            folder.delete();
        }
    }

    @Test
    public void captionTextJoinsNameAndCredit() {
        assertEquals("Dusk\nPhoto: Ada", WallpaperCaptions.caption("Dusk", "Photo: Ada"));
        assertEquals("Dusk", WallpaperCaptions.caption("Dusk", null));
        assertEquals("Photo: Ada", WallpaperCaptions.caption(null, "Photo: Ada"));
        assertNull(WallpaperCaptions.caption(null, null));
    }
}
