package com.oddin.oddsfeed.fakes;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * A server that speaks HTTP/2 over TLS and answers every request with 200 and a short body: the
 * JDK has no HTTP/2 server of its own, and {@link FakeRestServer} is HTTP/1.1. For the behaviour
 * of a client that only an HTTP/2 connection shows, such as the idle timeout the JDK's client keeps
 * on one. Just enough of the protocol for that: it does not decode the request, keeps no flow
 * control and answers a request with one HEADERS and one DATA frame.
 */
public final class FakeH2Server implements AutoCloseable {

    private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(UTF_8);
    private static final byte[] BODY = "<response/>".getBytes(UTF_8);

    private static final int DATA = 0x0;
    private static final int HEADERS = 0x1;
    private static final int SETTINGS = 0x4;
    private static final int PING = 0x6;
    private static final int GOAWAY = 0x7;
    private static final int END_STREAM = 0x1;
    private static final int ACK = 0x1;
    private static final int END_HEADERS = 0x4;
    /** The HPACK index of {@code :status: 200}, in the static table. */
    private static final int STATUS_200 = 0x88;

    private final SSLServerSocket server;
    private final List<String> protocols = new CopyOnWriteArrayList<>();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();

    private FakeH2Server() throws IOException {
        server = (SSLServerSocket) TestTls.serverContext()
                .getServerSocketFactory()
                .createServerSocket(0, 0, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().name("fake-h2-accept").daemon().start(this::accept);
    }

    public static FakeH2Server start() {
        try {
            return new FakeH2Server();
        } catch (IOException e) {
            throw new UncheckedIOException("could not start the fake HTTP/2 server", e);
        }
    }

    /** What to pass to the SDK as its API host: an IP, as {@link FakeRestServer#apiHost} gives. */
    public String apiHost() {
        return "127.0.0.1:" + server.getLocalPort();
    }

    /** The protocol each connection so far agreed on, "h2" for the ones that got HTTP/2. */
    public List<String> negotiatedProtocols() {
        return List.copyOf(protocols);
    }

    @Override
    public void close() {
        closeQuietly(server);
        sockets.forEach(FakeH2Server::closeQuietly);
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                var socket = (SSLSocket) server.accept();
                sockets.add(socket);
                var parameters = socket.getSSLParameters();
                parameters.setApplicationProtocols(new String[] {"h2"});
                socket.setSSLParameters(parameters);
                Thread.ofPlatform().name("fake-h2-connection").daemon().start(() -> serve(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(SSLSocket socket) {
        try (socket) {
            socket.startHandshake();
            protocols.add(socket.getApplicationProtocol());
            var in = new DataInputStream(socket.getInputStream());
            var out = socket.getOutputStream();
            in.readFully(new byte[PREFACE.length]);
            frame(out, SETTINGS, 0, 0, new byte[0]);
            while (true) {
                int length = (in.readUnsignedByte() << 16) | in.readUnsignedShort();
                int type = in.readUnsignedByte();
                int flags = in.readUnsignedByte();
                int stream = in.readInt() & 0x7fffffff;
                var payload = new byte[length];
                in.readFully(payload);
                switch (type) {
                    case SETTINGS -> {
                        if ((flags & ACK) == 0) {
                            frame(out, SETTINGS, ACK, 0, new byte[0]);
                        }
                    }
                    case PING -> {
                        if ((flags & ACK) == 0) {
                            frame(out, PING, ACK, 0, payload);
                        }
                    }
                    case HEADERS -> {
                        frame(out, HEADERS, END_HEADERS, stream, new byte[] {(byte) STATUS_200});
                        frame(out, DATA, END_STREAM, stream, BODY);
                    }
                    case GOAWAY -> {
                        return;
                    }
                    default -> {}
                }
            }
        } catch (EOFException closed) {
            // the client closed the connection
        } catch (IOException e) {
            // a connection closed under it, as the server's own close does
        }
    }

    private static void frame(OutputStream out, int type, int flags, int stream, byte[] payload) throws IOException {
        var header = new byte[9];
        header[0] = (byte) (payload.length >> 16);
        header[1] = (byte) (payload.length >> 8);
        header[2] = (byte) payload.length;
        header[3] = (byte) type;
        header[4] = (byte) flags;
        header[5] = (byte) (stream >> 24);
        header[6] = (byte) (stream >> 16);
        header[7] = (byte) (stream >> 8);
        header[8] = (byte) stream;
        out.write(header);
        out.write(payload);
        out.flush();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // closing is all that is asked
        }
    }
}
