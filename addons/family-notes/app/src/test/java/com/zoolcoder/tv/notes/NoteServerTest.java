package com.zoolcoder.tv.notes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** The server against real sockets on the loopback address: one connection per phone, and the time limits. */
public class NoteServerTest {
    private final PinGuard guard = new PinGuard(() -> 1L);
    private NoteServer server;
    private int port;

    @Before
    public void start() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        NoteEndpoint endpoint = new NoteEndpoint("<p>page</p>", () -> "123456", guard, () -> 1L, note -> { });
        server = new NoteServer(port, endpoint);
        server.start();
    }

    @After
    public void stop() {
        server.stop();
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
        socket.setSoTimeout(8000);
        return socket;
    }

    private static String readAll(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        try {
            for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
        } catch (IOException e) {
            // A reset after the answer still leaves what was read.
        }
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void write(Socket socket, String text) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    @Test
    public void servesThePage() throws IOException {
        try (Socket socket = connect()) {
            write(socket, "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            String answer = readAll(socket);
            assertTrue(answer, answer.startsWith("HTTP/1.1 200"));
        }
    }

    @Test
    public void aSecondConnectionFromTheSamePhoneIsBusy() throws Exception {
        try (Socket first = connect(); Socket second = connect()) {
            write(first, "GET / HTTP/1.1\r\n");
            String answer = readAll(second);
            assertTrue(answer, answer.startsWith("HTTP/1.1 503"));
        }
        // The slot comes back once the first one is gone.
        Thread.sleep(200);
        try (Socket again = connect()) {
            write(again, "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            assertTrue(readAll(again).startsWith("HTTP/1.1 200"));
        }
    }

    @Test
    public void aSilentConnectionIsClosedAfterAboutASecond() throws IOException {
        try (Socket socket = connect()) {
            long start = System.nanoTime();
            assertEquals("", readAll(socket));
            long took = (System.nanoTime() - start) / 1_000_000;
            assertTrue("took " + took + " ms", took >= NoteServer.IDLE_MS - 100 && took < NoteServer.HEAD_MS);
        }
    }

    @Test
    public void headersTrickledPastTwoSecondsAreDropped() throws Exception {
        try (Socket socket = connect()) {
            long start = System.nanoTime();
            String head = "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Slow: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\r\n\r\n";
            try {
                for (int i = 0; i < head.length() && System.nanoTime() - start < 4_000_000_000L; i++) {
                    write(socket, head.substring(i, i + 1));
                    Thread.sleep(500);
                }
            } catch (IOException closed) {
                // The server hung up mid-trickle, as it should.
            }
            String answer = readAll(socket);
            long took = (System.nanoTime() - start) / 1_000_000;
            assertEquals("", answer);
            assertTrue("took " + took + " ms", took < NoteServer.TIMEOUT_MS);
        }
    }

    @Test
    public void thePeerAddressReachesThePinCheck() throws IOException {
        for (int i = 0; i < PinGuard.MAX_WRONG; i++) {
            try (Socket socket = connect()) {
                String body = "name=a&text=b&pin=000000";
                write(socket, "POST /note HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/x-www-form-urlencoded\r\n"
                        + "Content-Length: " + body.length() + "\r\n\r\n" + body);
                assertTrue(readAll(socket).startsWith("HTTP/1.1 403"));
            }
        }
        // Only the loopback phone is locked out.
        assertEquals(PinGuard.Result.LOCKED, guard.check("127.0.0.1", "123456", "123456"));
        assertEquals(PinGuard.Result.OK, guard.check("10.0.0.2", "123456", "123456"));
    }
}
