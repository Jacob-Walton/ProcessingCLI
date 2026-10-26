package org.konpeki;

import java.awt.GraphicsEnvironment;
import processing.core.PGraphics;
import processing.core.PSurfaceNone;

/**
 * Surface with no window, frames go to the VS Code preview instead.
 */
public class PreviewSurface extends PSurfaceNone {
    public PreviewSurface(PGraphics graphics) {
        super(graphics);
    }

    // PSurfaceNone skips this when the size hasn't changed, leaving pixelWidth and pixelHeight at 0
    @Override
    public void setSize(int width, int height) {
        sketch.setSize(width, height);
        graphics.setSize(width, height);
    }

    @Override
    public int displayDensity() {
        return screenDensity();
    }

    @Override
    public int displayDensity(int display) {
        return screenDensity();
    }

    // PSurfaceNone always says 1, which would make sketches blurry on HiDPI screens
    private static int screenDensity() {
        try {
            double scale = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration()
                    .getDefaultTransform()
                    .getScaleX();
            return scale > 1 ? 2 : 1;
        } catch (Exception e) {
            return 1;
        }
    }
}
