package com.example.androidmcp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class CoordinateResolverTest {
    @Test public void resolvesAbsolutePixelsWithinDisplay() {
        CoordinateResolver.PointValue point =
                CoordinateResolver.resolve(1080, 2400, 100L, 200L, null, null);
        assertEquals(100, point.x);
        assertEquals(200, point.y);
    }

    @Test public void resolvesNormalizedCoordinatesAcrossResolution() {
        CoordinateResolver.PointValue center =
                CoordinateResolver.resolve(1080, 2400, null, null, 500, 500);
        assertEquals(540, center.x, 1);
        assertEquals(1200, center.y, 1);
        CoordinateResolver.PointValue edge =
                CoordinateResolver.resolve(1080, 2400, null, null, 1000, 1000);
        assertEquals(1079, edge.x);
        assertEquals(2399, edge.y);
    }

    @Test public void rejectsMixedMissingAndOutOfBoundsCoordinates() {
        assertThrows(IllegalArgumentException.class,
                () -> CoordinateResolver.resolve(1080, 2400, 1L, 2L, 10, 20));
        assertThrows(IllegalArgumentException.class,
                () -> CoordinateResolver.resolve(1080, 2400, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> CoordinateResolver.resolve(1080, 2400, 1080L, 0L, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> CoordinateResolver.resolve(1080, 2400, null, null, 1001, 0));
    }
}
