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
 * most 2 connections are handled at a time (a third gets 503); a request must arrive within 5 seconds in
 * total, and every answer closes the connection.
 */
final class NoteServer {
    static final int MAX_CONNECTIONS = 2;
    static final int TIMEOUT_MS = 5000;

    private final int port;
    private final NoteEndpoint endpoint;
    private final Semaphore slots = new Semaphore(MAX_CONNECTIONS);
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
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
                if (!AddressFilter.allowed(peer.getInetAddress())) {
                    close(peer);
                } else if (!slots.tryAcquire()) {
                    refuse(peer);
                } else {
                    open.add(peer);
                    Thread worker = new Thread(() -> serve(peer), "family-notes-conn");
                    worker.setDaemon(true);
                    worker.start();
                }
            } catch (IOException e) {
                if (socket.isClosed()) return;
            }
        }
    }

    private void serve(Socket peer) {
        try {
            peer.setSoTimeout(TIMEOUT_MS);
            InputStream in = new BufferedInputStream(new Deadline(peer.getInputStream(), TIMEOUT_MS));
            Response response;
            try {
                response = endpoint.handle(HttpRequest.read(in));
            } catch (HttpException e) {
                response = Response.text(e.status, e.getMessage());
            }
            send(peer, response);
        } catch (IOException ignored) {
            // A phone that went away or was too slow: nothing to answer.
        } finally {
            open.remove(peer);
            close(peer);
            slots.release();
        }
    }

    private static void refuse(Socket peer) {
        try {
            peer.setSoTimeout(TIMEOUT_MS);
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

    /** Fails reads once the whole request has taken longer than the limit, so a trickle cannot hold a slot. */
    private static final class Deadline extends InputStream {
        private final InputStream in;
        private final long end;

        Deadline(InputStream in, int millis) {
            this.in = in;
            this.end = System.nanoTime() + millis * 1_000_000L;
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
