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
import java.nio.IntBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import processing.core.PApplet;
import processing.core.PGraphics;
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
    // The preview asked for run-length encoded frames, older ones only read raw pixels
    private volatile SocketChannel compressing;
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
                + " if (!org.konpeki.Preview.active()) return super.initSurface();"
                + " g = createPrimaryGraphics();"
                + " surface = org.konpeki.Preview.surface(this, g);"
                + " return surface; }\n"
                + "  @Override protected void showSurface() {"
                + " if (org.konpeki.Preview.shouldShow(this)) super.showSurface(); }\n"
                + "  @Override public void exitActual() {"
                + " if (!org.konpeki.Preview.stopped(this)) super.exitActual(); }\n"
                + code.substring(end);
    }

    public static boolean active() {
        return Boolean.getBoolean("processingcli.preview");
    }

    // Java2D draws into its own image with no window. OpenGL needs a window for its context, kept off screen
    // (JOGL's truly off-screen window takes over a second to create, too slow for reloads)
    public static PSurface surface(PApplet sketch, PGraphics graphics) {
        if (graphics.isGL()) {
            PSurface surface = new PreviewSurfaceJOGL(graphics);
            surface.initFrame(sketch);
            return surface;
        }
        PSurface surface = new PreviewSurface(graphics);
        surface.initOffscreen(sketch);
        return surface;
    }

    // OpenGL only draws while its window is visible, so show it off screen
    public static boolean shouldShow(PApplet sketch) {
        PSurface surface = sketch.getSurface();
        if (surface instanceof PreviewSurface) return false;
        if (surface instanceof PreviewSurfaceJOGL) surface.setLocation(-30000, -30000);
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
        private final ExecutorService sender =
                Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        // Two buffers: while one frame sends, the next waits in the other instead of being dropped.
        // A newer frame replaces a waiting one, so the preview never falls behind
        private final ByteBuffer[] buffers = new ByteBuffer[2];
        private ByteBuffer sending, waiting;
        private boolean announced;

        Frames(PApplet owner) {
            this.owner = owner;
        }

        public void post() {
            SocketChannel channel = session.client;
            if (owner != session.sketch || channel == null) return;
            int pw = owner.pixelWidth, ph = owner.pixelHeight;
            int[] pixels = pixels();
            if (pixels == null || pixels.length < pw * ph) return;

            int slot;
            synchronized (this) {
                slot = buffers[0] != null && buffers[0] == sending ? 1 : 0;
                if (waiting == buffers[slot]) waiting = null;
            }
            // Binary WebSocket message: pixelWidth, pixelHeight, width, height, then ARGB ints (BGRA bytes).
            // The top bit of pixelWidth marks run-length encoded pixels, see encode
            boolean packed = session.compressing == channel;
            // Room for the longest WebSocket header, and encoding never takes more than one int over the raw pixels
            int capacity = 10 + 16 + (pw * ph + 1) * 4;
            if (buffers[slot] == null || buffers[slot].capacity() < capacity) {
                buffers[slot] = ByteBuffer.allocateDirect(capacity);
            }
            ByteBuffer frame = buffers[slot];
            frame.clear().position(10);
            frame.order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(packed ? pw | 0x80000000 : pw)
                    .putInt(ph)
                    .putInt(owner.width)
                    .putInt(owner.height);
            IntBuffer ints = frame.asIntBuffer();
            int count = packed
                    ? encode(pixels, pw * ph, ints)
                    : ints.put(pixels, 0, pw * ph).position();
            int payload = 16 + count * 4;
            // Browsers drop the connection unless the length takes as few bytes as it can, so the header
            // goes just before the payload once its size is known
            frame.order(ByteOrder.BIG_ENDIAN);
            int start;
            if (payload < 126) {
                start = 8;
                frame.put(start + 1, (byte) payload);
            } else if (payload < 65536) {
                start = 6;
                frame.put(start + 1, (byte) 126).putShort(start + 2, (short) payload);
            } else {
                start = 0;
                frame.put(start + 1, (byte) 127).putLong(start + 2, payload);
            }
            frame.put(start, (byte) 0x82);
            frame.limit(10 + payload).position(start);

            boolean first = !announced;
            announced = true;
            synchronized (this) {
                if (sending != null) {
                    waiting = frame;
                    return;
                }
                sending = frame;
            }
            sender.execute(() -> send(channel, first));
        }

        // Sends the frame, then any that came in meanwhile
        private void send(SocketChannel channel, boolean first) {
            try {
                if (first) session.status("running");
                while (true) {
                    ByteBuffer frame;
                    synchronized (this) {
                        frame = sending;
                    }
                    session.send(channel, frame);
                    synchronized (this) {
                        sending = waiting;
                        waiting = null;
                        if (sending == null) return;
                    }
                }
            } catch (IOException e) {
                // Preview went away, wait for it to reconnect
                synchronized (this) {
                    sending = null;
                    waiting = null;
                }
            }
        }

        // A run of one colour as [count, colour], anything else as [-count, colours...]. Most sketches are
        // big areas of one colour, and some PCs can't send raw frames over localhost fast enough
        static int encode(int[] pixels, int length, IntBuffer out) {
            int i = 0;
            while (i < length) {
                int colour = pixels[i];
                int end = i + 1;
                while (end < length && pixels[end] == colour) end++;
                if (end - i >= 3) {
                    out.put(end - i).put(colour);
                    i = end;
                    continue;
                }
                // Different colours up to the next run of three
                int start = i;
                while (i < length && !(i + 2 < length && pixels[i] == pixels[i + 1] && pixels[i] == pixels[i + 2])) {
                    i++;
                }
                out.put(start - i).put(pixels, start, i - start);
            }
            return out.position();
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
                if (opcode == 2 && payload.length == 1 && payload[0] == 'C') compressing = channel;
                else if (opcode == 2) handle(ByteBuffer.wrap(payload));
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
        // The preview drew a frame, see Pacer
        if (input.get(0) == 'T') {
            Pacer.tick();
            return;
        }
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
