package com.example.androidmcp;

import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class UpdateManagerTest {
    @Test public void acceptHeaderIsSetBeforeConnectionRead() throws Exception {
        RecordingConnection connection = new RecordingConnection();

        UpdateValidation.configureRequest(connection, "application/vnd.github+json");
        connection.getResponseCode();

        assertEquals(List.of("Accept", "User-Agent", "response"), connection.events);
    }

    private static final class RecordingConnection extends HttpURLConnection {
        final List<String> events = new ArrayList<>();

        RecordingConnection() throws IOException {
            super(new URL("https://api.github.com/"));
        }

        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }

        @Override public void setRequestProperty(String key, String value) {
            events.add(key);
            super.setRequestProperty(key, value);
        }

        @Override public int getResponseCode() {
            events.add("response");
            return 200;
        }
    }
}
