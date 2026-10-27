package com.zoolcoder.tv.calendar;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * A tiny HTTP/1.1 server on a java.net.ServerSocket. Peers outside the home network are closed at once; at
 * most 8 connections are handled at a time and one per address (more get 503), so one device holding
 * sockets open cannot shut the family out. A connection that sends nothing for a second is closed; the
 * headers must arrive within 2 seconds and the whole request within 5, and every answer closes the connection.
 */
final class CalendarServer {
    static final int MAX_CONNECTIONS = 8;
    static final int IDLE_MS = 1000;
    static final int HEAD_MS = 2000;
    static final int TIMEOUT_MS = 5000;
    /** The pause after accept() fails for another reason than a closed socket, such as too many open files. */
    static final int ACCEPT_BACKOFF_MS = 200;

    private final int port;
    private final CalendarEndpoint endpoint;
    private final Semaphore slots = new Semaphore(MAX_CONNECTIONS);
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final Set<InetAddress> busy = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket server;

    CalendarServer(int port, CalendarEndpoint endpoint) {
        this.port = port;
        this.endpoint = endpoint;
    }

    /** Binds the port and starts accepting; throws IOException when the port cannot be used. */
    void start() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        try {
            socket.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        server = socket;
        Thread loop = new Thread(() -> accept(socket), "family-calendar-accept");
        loop.setDaemon(true);
        loop.start();
    }

    void stop() {
        ServerSocket socket = server;
        server = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing only unblocks the accept loop.
            }
        }
        for (Socket peer : open) close(peer);
    }

    private void accept(ServerSocket socket) {
        while (!socket.isClosed()) {
            try {
                Socket peer = socket.accept();
                InetAddress address = peer.getInetAddress();
                if (!AddressFilter.allowed(address)) {
                    close(peer);
                } else if (!busy.add(address)) {
                    refuse(peer);
                } else if (!slots.tryAcquire()) {
                    busy.remove(address);
                    refuse(peer);
                } else {
                    open.add(peer);
                    Thread worker = new Thread(() -> serve(peer, address), "family-calendar-conn");
                    worker.setDaemon(true);
                    worker.start();
                }
            } catch (IOException e) {
                if (socket.isClosed()) return;
                pause();
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(ACCEPT_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void serve(Socket peer, InetAddress address) {
        try {
            peer.setSoTimeout(IDLE_MS);
            InputStream in = new Deadline(new BufferedInputStream(peer.getInputStream()), HEAD_MS, TIMEOUT_MS);
            Response response;
            try {
                response = endpoint.handle(HttpRequest.read(in, address.getHostAddress()));
            } catch (HttpException e) {
                response = Response.text(e.status, e.getMessage());
            }
            send(peer, response);
        } catch (IOException ignored) {
            // A phone that went away or was too slow: nothing to answer.
        } finally {
            open.remove(peer);
            close(peer);
            busy.remove(address);
            slots.release();
        }
    }

    private static void refuse(Socket peer) {
        try {
            peer.setSoTimeout(IDLE_MS);
            send(peer, Response.text(503, "busy"));
        } catch (IOException ignored) {
            // Busy and gone: nothing to do.
        } finally {
            close(peer);
        }
    }

    private static void send(Socket peer, Response response) throws IOException {
        OutputStream out = peer.getOutputStream();
        out.write(response.toBytes());
        out.flush();
    }

    private static void close(Socket peer) {
        try {
            peer.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    /**
     * Fails reads once the headers have taken longer than their limit, or the whole request longer than its
     * own, so a trickle cannot hold a slot. Sits outside the buffer, so it sees the head byte by byte.
     */
    static final class Deadline extends InputStream {
        private final InputStream in;
        private final long headEnd, end;
        private int matched;

        Deadline(InputStream in, int headMillis, int totalMillis) {
            this.in = in;
            long now = System.nanoTime();
            this.headEnd = now + headMillis * 1_000_000L;
            this.end = now + totalMillis * 1_000_000L;
        }

        private void check() throws SocketTimeoutException {
            if (System.nanoTime() > (matched < 4 ? headEnd : end)) throw new SocketTimeoutException("request too slow");
        }

        @Override
        public int read() throws IOException {
            check();
            int b = in.read();
            if (matched < 4) {
                boolean next = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3));
                matched = next ? matched + 1 : (b == '\r' ? 1 : 0);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            check();
            // Until the head is over, one byte at a time so the end of the headers is seen.
            if (matched < 4) {
                if (len == 0) return 0;
                int one = read();
                if (one < 0) return -1;
                b[off] = (byte) one;
                return 1;
            }
            return in.read(b, off, len);
        }
    }
}
