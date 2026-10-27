package com.zoolcoder.tv.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/** A phone that sends its headers slowly is cut off at the head deadline; the body gets the longer one. */
public class DeadlineTest {
    private static InputStream stream(String raw, int headMillis, int totalMillis) {
        return new CalendarServer.Deadline(new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)),
                headMillis, totalMillis);
    }

    @Test
    public void slowHeadersAreCutOff() throws Exception {
        InputStream in = stream("POST / HTTP/1.1\r\nHost: x\r\n", 40, 5000);
        in.read();
        Thread.sleep(60);
        try {
            in.read();
            fail("read after the head deadline");
        } catch (SocketTimeoutException expected) {
            // The head took too long.
        }
    }

    @Test
    public void theBodyMayComeAfterTheHeadDeadline() throws Exception {
        InputStream in = stream("POST / HTTP/1.1\r\n\r\nbody", 40, 5000);
        byte[] buffer = new byte[64];
        int head = 0;
        while (head < 19) head += in.read(buffer, head, buffer.length - head);
        Thread.sleep(60);
        assertEquals(4, in.read(buffer, 0, buffer.length));
    }

    @Test
    public void theHeadIsReadOneByteAtATime() throws IOException {
        InputStream in = stream("GET / HTTP/1.1\r\n\r\n", 1000, 5000);
        assertEquals(1, in.read(new byte[64], 0, 64));
    }
}
