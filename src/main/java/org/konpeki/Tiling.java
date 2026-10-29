package org.konpeki;

import com.jogamp.newt.opengl.GLWindow;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import processing.core.PApplet;

/**
 * Hyprland tiles P2D and P3D windows, JOGL sets their size hints too late
 */
public final class Tiling {
    private static final String HYPRLAND = System.getenv("HYPRLAND_INSTANCE_SIGNATURE");

    private Tiling() {}

    public static void show(PApplet sketch, Runnable show) {
        if (HYPRLAND == null || !(sketch.getSurface().getNative() instanceof GLWindow window)) {
            show.run();
            return;
        }
        int width = window.getWidth(), height = window.getHeight();
        show.run();
        try {
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (!window.isNativeValid() || !window.isVisible()) {
                if (System.nanoTime() > deadline) return;
                Thread.sleep(5);
            }
            window.setVisible(false);
            window.setVisible(true);
            // Before the first frame, or Processing warns it was resized
            while (window.getWidth() != width || window.getHeight() != height) {
                if (System.nanoTime() > deadline) break;
                Thread.sleep(5);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Redo GL setup, Processing skipped it on NEWT's thread
        window.getScreen().getDisplay().getEDTUtil().invoke(true, () -> {
            for (int i = 0; i < window.getGLEventListenerCount(); i++) {
                window.setGLEventListenerInitState(window.getGLEventListener(i), false);
            }
        });
        // Can't go off screen here, and OpenGL stops when hidden
        if (sketch.getSurface() instanceof PreviewSurfaceJOGL) {
            String pid = String.valueOf(ProcessHandle.current().pid());
            if (!hyprctl("dispatch hl.dsp.window.move({ workspace = 'special:processingcli', follow = false,"
                    + " window = 'pid:" + pid + "' })")) {
                // Before Hyprland 0.56
                hyprctl("dispatch movetoworkspacesilent special:processingcli,pid:" + pid);
            }
        }
    }

    private static boolean hyprctl(String command) {
        Path socket = Path.of(System.getenv("XDG_RUNTIME_DIR"), "hypr", HYPRLAND, ".socket.sock");
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            channel.write(ByteBuffer.wrap(command.getBytes(StandardCharsets.UTF_8)));
            ByteBuffer reply = ByteBuffer.allocate(256);
            channel.read(reply);
            return new String(reply.array(), 0, reply.position(), StandardCharsets.UTF_8).equals("ok");
        } catch (Exception e) {
            return false;
        }
    }
}
