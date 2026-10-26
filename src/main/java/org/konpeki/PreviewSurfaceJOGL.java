package org.konpeki;

import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import processing.core.PGraphics;
import processing.opengl.PSurfaceJOGL;

/**
 * OpenGL surface for the preview. Its window is only there for the GL context, it sits off screen
 * and stays off the taskbar. Frames are timed to the preview's display refresh.
 */
public class PreviewSurfaceJOGL extends PSurfaceJOGL {
    private volatile long period = 1_000_000_000L / 60;

    public PreviewSurfaceJOGL(PGraphics graphics) {
        super(graphics);
    }

    @Override
    protected void initWindow() {
        super.initWindow();
        // NEWT creates the native window on its event thread when it's first shown
        display.getEDTUtil().invoke(true, NoTaskbar::hookCurrentThread);
        // Runs before Processing's own listener on every frame
        window.addGLEventListener(0, new GLEventListener() {
            private final Pacer pacer = new Pacer();

            @Override
            public void init(GLAutoDrawable drawable) {}

            @Override
            public void display(GLAutoDrawable drawable) {
                // Vsync would make each frame wait for the real monitor, the preview's ticks do the timing
                if (drawable.getGL().getSwapInterval() != 0) drawable.getGL().setSwapInterval(0);
                // Processing's animator calls display() at least as often as needed, hold it here until the
                // next frame is due. Not on NEWT's thread, which only repaints
                if (display.getEDTUtil().isCurrentThreadEDT()) return;
                try {
                    pacer.await(period);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {}

            @Override
            public void dispose(GLAutoDrawable drawable) {}
        });
    }

    // Processing asks for focus on the first frame. Windows won't give it to a background
    // window, so it flashes the taskbar button instead
    @Override
    public void requestFocus() {}

    @Override
    public void setFrameRate(float fps) {
        if (fps > 0) period = (long) (1_000_000_000L / fps);
        super.setFrameRate(fps);
    }
}
