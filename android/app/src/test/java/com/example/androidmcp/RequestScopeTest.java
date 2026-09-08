package com.example.androidmcp;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RequestScopeTest {
    @Test public void cancellationFencesQueuedActionsAndClosesReads() throws Exception {
        RequestScope scope = new RequestScope();
        AtomicBoolean closed = new AtomicBoolean();
        scope.attach(() -> closed.set(true));
        scope.cancel();
        AtomicBoolean busy = new AtomicBoolean();
        assertThrows(ApiException.class, () -> scope.beginAction(busy));
        assertFalse(busy.get());
        assertTrue(closed.get());
    }
    @Test public void inFlightNativeActionRemainsBusyUntilNativeCompletion() throws Exception {
        RequestScope first = new RequestScope();
        AtomicBoolean busy = new AtomicBoolean();
        first.beginAction(busy);
        first.cancel();
        RequestScope second = new RequestScope();
        assertThrows(ApiException.class, () -> second.beginAction(busy));
        busy.set(false); // Android completion callback, not the cancelled wait, releases the action.
        second.beginAction(busy);
        assertTrue(busy.get());
    }
}
