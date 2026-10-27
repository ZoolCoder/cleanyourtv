package com.zoolcoder.tv.notes;

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
 * most 8 connections are handled at a time and one per phone (more get 503); the headers must arrive within
 * 2 seconds and the whole request within 5, a connection that sends nothing for 1 second is closed, and every
 * answer closes the connection.
 */
final class NoteServer {
    static final int MAX_CONNECTIONS = 8;
    static final int HEAD_MS = 2000;
    static final int TIMEOUT_MS = 5000;
    static final int IDLE_MS = 1000;
    /** Pause after a failed accept (out of file descriptors, say), so the loop cannot spin. */
    static final int ACCEPT_BACKOFF_MS = 200;

    private final int port;
    private final NoteEndpoint endpoint;
    private final Semaphore slots = new Semaphore(MAX_CONNECTIONS);
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final Set<InetAddress> busy = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket server;

    NoteServer(int port, NoteEndpoint endpoint) {
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
        Thread loop = new Thread(() -> accept(socket), "family-notes-accept");
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
                    Thread worker = new Thread(() -> serve(peer), "family-notes-conn");
                    worker.setDaemon(true);
                    worker.start();
                }
            } catch (IOException e) {
                if (socket.isClosed()) return;
                pause();
            }
        }
    }

    private void serve(Socket peer) {
        try {
            peer.setSoTimeout(IDLE_MS);
            Deadline deadline = new Deadline(peer.getInputStream(), HEAD_MS);
            InputStream in = new BufferedInputStream(deadline);
            String address = peer.getInetAddress().getHostAddress();
            Response response;
            try {
                response = endpoint.handle(HttpRequest.read(in, address, () -> deadline.extend(TIMEOUT_MS)));
            } catch (HttpException e) {
                response = Response.text(e.status, e.getMessage());
            }
            send(peer, response);
        } catch (IOException ignored) {
            // A phone that went away or was too slow: nothing to answer.
        } finally {
            open.remove(peer);
            busy.remove(peer.getInetAddress());
            close(peer);
            slots.release();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(ACCEPT_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    /** Fails reads once the request has taken longer than the limit, so a trickle cannot hold a slot. */
    static final class Deadline extends InputStream {
        private final InputStream in;
        private final long start;
        private volatile long end;

        Deadline(InputStream in, int millis) {
            this.in = in;
            this.start = System.nanoTime();
            this.end = start + millis * 1_000_000L;
        }

        /** Moves the limit to millis after the start, once the headers are in and the body may follow. */
        void extend(int millis) {
            end = start + millis * 1_000_000L;
        }

        private void check() throws SocketTimeoutException {
            if (System.nanoTime() > end) throw new SocketTimeoutException("request too slow");
        }

        @Override
        public int read() throws IOException {
            check();
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            check();
            return in.read(b, off, len);
        }
    }
}
