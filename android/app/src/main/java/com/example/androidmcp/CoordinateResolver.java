package com.example.androidmcp;

/** Converts either absolute pixels or 0..1000 normalized coordinates into display pixels. */
public final class CoordinateResolver {
    private CoordinateResolver() { }

    public static PointValue resolve(int width, int height,
                                     Long x, Long y, Integer nx, Integer ny) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Invalid display size");
        boolean absolute = x != null || y != null;
        boolean normalized = nx != null || ny != null;
        if (absolute == normalized) throw new IllegalArgumentException("Choose one coordinate mode");
        if (absolute) {
            if (x == null || y == null || x < 0 || y < 0 || x >= width || y >= height) {
                throw new IllegalArgumentException("Pixel coordinate outside display");
            }
            return new PointValue(x.intValue(), y.intValue());
        }
        if (nx == null || ny == null || nx < 0 || nx > 1000 || ny < 0 || ny > 1000) {
            throw new IllegalArgumentException("Normalized coordinate outside 0..1000");
        }
        int px = (int) Math.round((nx / 1000.0) * (width - 1));
        int py = (int) Math.round((ny / 1000.0) * (height - 1));
        return new PointValue(px, py);
    }

    public static final class PointValue {
        public final int x;
        public final int y;
        PointValue(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}
