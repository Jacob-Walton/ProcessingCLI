package org.konpeki;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import processing.core.PApplet;
import processing.core.PSurface;
import processing.event.KeyEvent;
import processing.event.MouseEvent;

public class Preview {
    // Tells the extension to start a fresh ProcessingCLI
    static final int RESTART = 75;

    private static final Pattern KEY = Pattern.compile("(?i)Sec-WebSocket-Key:\\s*(\\S+)");
    private static Preview session;

    private final String token;
    private final ServerSocketChannel server;
    private final Object writing = new Object();
    private volatile SocketChannel client;
    private volatile PApplet sketch;
    private volatile CountDownLatch stopped = new CountDownLatch(0);
    private volatile String status = "starting";

    private Preview(ServerSocketChannel server, String token) {
        this.server = server;
        this.token = token;
    }

    // spec is PORT/TOKEN
    static void start(String spec) throws IOException {
        int slash = spec.indexOf('/');
        int port = Integer.parseInt(spec.substring(0, slash));
        session = new Preview(
                ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", port)), spec.substring(slash + 1));
        System.setProperty("processingcli.preview", "true");
        Thread.ofPlatform().daemon().start(session::accept);

        // An exception in setup() or draw() kills the sketch's thread without exitActual()
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            e.printStackTrace();
            session.status("error");
            session.stopped.countDown();
        });
    }

    static void attach(PApplet sketch) {
        if (session == null) return;
        session.stopped = new CountDownLatch(1);
        session.sketch = sketch;
        sketch.registerMethod("post", new Frames(sketch));
    }

    static void failed() {
        if (session != null) session.status("error");
    }

    // One "reload" line per save, until the extension closes stdin
    static void serveReloads(BooleanSupplier run) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        for (String line; (line = in.readLine()) != null; ) {
            if (!line.trim().equals("reload")) continue;
            session.status("reloading");
            if (!session.stopCurrent()) {
                System.err.println("The sketch didn't stop, restarting the preview");
                System.exit(RESTART);
            }
            run.getAsBoolean();
        }
        System.exit(0);
    }

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
                + "  @Override public void exitActual() {"
                + " if (!org.konpeki.Preview.stopped(this)) super.exitActual(); }\n"
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

    // Instead of System.exit, the sketch just stops and the preview waits for the next save
    public static boolean stopped(PApplet sketch) {
        if (session == null) return false;
        // Not a reload, the sketch called exit() itself (or Esc was pressed)
        if (sketch == session.sketch) session.status("stopped");
        session.stopped.countDown();
        return true;
    }

    private boolean stopCurrent() {
        PApplet old = sketch;
        if (old == null) return true;
        // Its last frames and any input go nowhere from here
        sketch = null;
        old.exit();
        boolean done;
        try {
            done = stopped.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            done = false;
        }
        old.getSurface().stopThread();
        // OpenGL sketches have a hidden window to close
        Object window = old.getSurface().getNative();
        if (window != null && window.getClass().getName().endsWith("GLWindow")) {
            try {
                window.getClass().getMethod("destroy").invoke(window);
            } catch (Exception ignored) {
            }
        }
        return done;
    }

    /** Sends each finished frame of one sketch, registered as its "post" method. */
    public static final class Frames {
        private final PApplet owner;
        // Drop frames while the last one is still sending, so the sketch doesn't wait on the preview
        private final AtomicBoolean sending = new AtomicBoolean();
        private final ExecutorService sender =
                Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        private ByteBuffer frame;
        private boolean announced;

        Frames(PApplet owner) {
            this.owner = owner;
        }

        public void post() {
            SocketChannel channel = session.client;
            if (owner != session.sketch || channel == null || !sending.compareAndSet(false, true)) return;
            int pw = owner.pixelWidth, ph = owner.pixelHeight;
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
                    .putInt(owner.width)
                    .putInt(owner.height);
            frame.asIntBuffer().put(pixels, 0, pw * ph);
            frame.position(10 + payload).flip();

            boolean first = !announced;
            announced = true;
            sender.execute(() -> {
                try {
                    if (first) session.status("running");
                    session.send(channel, frame);
                } catch (IOException ignored) {
                    // Preview went away, wait for it to reconnect
                } finally {
                    sending.set(false);
                }
            });
        }

        // Java2D draws into a BufferedImage, read it directly instead of copying through loadPixels
        private int[] pixels() {
            if (owner.g.image instanceof BufferedImage image
                    && image.getRaster().getDataBuffer() instanceof DataBufferInt data) {
                return data.getData();
            }
            owner.loadPixels();
            return owner.pixels;
        }
    }

    // Text messages for the preview's overlay: starting, reloading, running, error, stopped
    private void status(String type) {
        status = type;
        SocketChannel channel = client;
        if (channel == null) return;
        try {
            sendStatus(channel, type);
        } catch (IOException ignored) {
        }
    }

    private void sendStatus(SocketChannel channel, String type) throws IOException {
        byte[] json = ("{\"type\":\"" + type + "\"}").getBytes(StandardCharsets.UTF_8);
        ByteBuffer message = ByteBuffer.allocate(2 + json.length);
        message.put((byte) 0x81).put((byte) json.length).put(json).flip();
        send(channel, message);
    }

    private void send(SocketChannel channel, ByteBuffer buffer) throws IOException {
        synchronized (writing) {
            while (buffer.hasRemaining()) channel.write(buffer);
        }
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
                // A reopened preview needs to know where things are
                sendStatus(channel, status);
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
        send(channel, ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII)));
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
        PApplet target = sketch;
        if (target == null) return;
        long now = System.currentTimeMillis();
        switch (input.get()) {
            case 'M' -> {
                int action = input.get();
                int x = input.getInt(), y = input.getInt();
                int button = input.getInt(), count = input.getInt(), modifiers = input.getInt();
                target.postEvent(new MouseEvent(null, now, action, modifiers, x, y, button, count));
            }
            case 'K' -> {
                int action = input.get();
                char key = input.getChar();
                int keyCode = input.getInt(), modifiers = input.getInt();
                target.postEvent(new KeyEvent(null, now, action, modifiers, key, keyCode, false));
            }
            default -> {}
        }
    }
}
