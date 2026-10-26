package org.konpeki;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import processing.core.PApplet;
import processing.core.PSurface;
import processing.event.KeyEvent;
import processing.event.MouseEvent;

/**
 * WebSocket server the VS Code preview connects to.
 */
public class Preview {
    private static final Pattern KEY = Pattern.compile("(?i)Sec-WebSocket-Key:\\s*(\\S+)");

    private final PApplet sketch;
    private final String token;
    private final ServerSocketChannel server;
    private volatile SocketChannel client;
    // Drop frames while the last one is still sending, so the sketch doesn't wait on the preview
    private final AtomicBoolean sending = new AtomicBoolean();
    private final ExecutorService sender =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
    private ByteBuffer frame;

    private Preview(PApplet sketch, ServerSocketChannel server, String token) {
        this.sketch = sketch;
        this.server = server;
        this.token = token;
    }

    // spec is PORT/TOKEN
    public static void attach(PApplet sketch, String spec) throws IOException {
        int slash = spec.indexOf('/');
        int port = Integer.parseInt(spec.substring(0, slash));
        Preview preview = new Preview(sketch, bind(port), spec.substring(slash + 1));
        System.setProperty("processingcli.preview", "true");
        sketch.registerMethod("post", preview);
        Thread.ofPlatform().daemon().start(preview::accept);
    }

    // The extension waits for the previous preview process to exit before starting this one
    private static ServerSocketChannel bind(int port) throws IOException {
        return ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", port));
    }

    // Swaps in a surface with no window in preview mode
    static String hideable(String code) {
        int end = code.lastIndexOf('}');
        return code.substring(0, end)
                + "  @Override protected processing.core.PSurface initSurface() {"
                + " if (!org.konpeki.Preview.headless(this)) return super.initSurface();"
                + " g = createPrimaryGraphics();"
                + " surface = new org.konpeki.PreviewSurface(g);"
                + " surface.initOffscreen(this);"
                + " return surface; }\n"
                + "  @Override protected void showSurface() {"
                + " if (org.konpeki.Preview.shouldShow(this)) super.showSurface(); }\n"
                + code.substring(end);
    }

    // OpenGL still needs its window for a GL context
    public static boolean headless(PApplet sketch) {
        return Boolean.getBoolean("processingcli.preview")
                && !sketch.sketchRenderer().startsWith("processing.opengl");
    }

    public static boolean shouldShow(PApplet sketch) {
        if (!Boolean.getBoolean("processingcli.preview")) return true;
        PSurface surface = sketch.getSurface();
        if (surface instanceof PreviewSurface) return false;
        // OpenGL only draws while the window is visible, so show it off screen
        surface.setLocation(-30000, -30000);
        return true;
    }

    public void post() {
        SocketChannel channel = client;
        if (channel == null || !sending.compareAndSet(false, true)) return;
        int pw = sketch.pixelWidth, ph = sketch.pixelHeight;
        int[] pixels = pixels();
        if (pixels == null || pixels.length < pw * ph) {
            sending.set(false);
            return;
        }

        // Binary WebSocket message: pixelWidth, pixelHeight, width, height, then ARGB ints (BGRA bytes)
        int payload = 16 + pw * ph * 4;
        if (frame == null || frame.capacity() < 10 + payload) {
            frame = ByteBuffer.allocateDirect(10 + payload);
        }
        frame.clear();
        frame.order(ByteOrder.BIG_ENDIAN).put((byte) 0x82).put((byte) 127).putLong(payload);
        frame.order(ByteOrder.LITTLE_ENDIAN)
                .putInt(pw)
                .putInt(ph)
                .putInt(sketch.width)
                .putInt(sketch.height);
        frame.asIntBuffer().put(pixels, 0, pw * ph);
        frame.position(10 + payload).flip();

        sender.execute(() -> {
            try {
                write(channel, frame);
            } catch (IOException ignored) {
                // Preview went away, wait for it to reconnect
            } finally {
                sending.set(false);
            }
        });
    }

    // Java2D draws into a BufferedImage, read it directly instead of copying through loadPixels
    private int[] pixels() {
        if (sketch.g.image instanceof BufferedImage image
                && image.getRaster().getDataBuffer() instanceof DataBufferInt data) {
            return data.getData();
        }
        sketch.loadPixels();
        return sketch.pixels;
    }

    private void accept() {
        while (true) {
            try {
                SocketChannel channel = server.accept();
                channel.socket().setTcpNoDelay(true);
                if (!handshake(channel)) {
                    channel.close();
                    continue;
                }
                SocketChannel previous = client;
                client = channel;
                if (previous != null) previous.close();
                Thread.ofPlatform().daemon().start(() -> readInput(channel));
            } catch (IOException e) {
                return;
            }
        }
    }

    private boolean handshake(SocketChannel channel) throws IOException {
        InputStream in = channel.socket().getInputStream();
        StringBuilder request = new StringBuilder();
        while (request.length() < 4 || !request.substring(request.length() - 4).equals("\r\n\r\n")) {
            int b = in.read();
            if (b < 0 || request.length() > 8192) return false;
            request.append((char) b);
        }
        Matcher key = KEY.matcher(request);
        if (!request.toString().startsWith("GET /" + token + " ") || !key.find()) return false;

        String accept;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest((key.group(1) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                            .getBytes(StandardCharsets.US_ASCII));
            accept = Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            return false;
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        write(channel, ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII)));
        return true;
    }

    private void readInput(SocketChannel channel) {
        try {
            DataInputStream in =
                    new DataInputStream(new BufferedInputStream(channel.socket().getInputStream()));
            while (true) {
                int opcode = in.readUnsignedByte() & 0x0F;
                int second = in.readUnsignedByte();
                long length = second & 0x7F;
                if (length == 126) {
                    length = in.readUnsignedShort();
                } else if (length == 127) {
                    length = in.readLong();
                }
                byte[] mask = new byte[4];
                if ((second & 0x80) != 0) in.readFully(mask);
                byte[] payload = new byte[(int) length];
                in.readFully(payload);
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

                if (opcode == 8) break;
                if (opcode == 2) handle(ByteBuffer.wrap(payload));
            }
        } catch (IOException ignored) {
            // Preview closed or reloading
        }
        if (client == channel) client = null;
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    private void handle(ByteBuffer input) {
        long now = System.currentTimeMillis();
        switch (input.get()) {
            case 'M' -> {
                int action = input.get();
                int x = input.getInt(), y = input.getInt();
                int button = input.getInt(), count = input.getInt(), modifiers = input.getInt();
                sketch.postEvent(new MouseEvent(null, now, action, modifiers, x, y, button, count));
            }
            case 'K' -> {
                int action = input.get();
                char key = input.getChar();
                int keyCode = input.getInt(), modifiers = input.getInt();
                sketch.postEvent(new KeyEvent(null, now, action, modifiers, key, keyCode, false));
            }
            default -> {}
        }
    }

    private static void write(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) channel.write(buffer);
    }
}
