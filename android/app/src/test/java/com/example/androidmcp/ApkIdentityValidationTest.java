package com.example.androidmcp;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertThrows;

public final class ApkIdentityValidationTest {
    @Test public void acceptsExpectedPackageVersionCodeAndSigner() {
        ApkIdentityValidation.requireValidCandidate(
                "com.example.androidmcp", "0.6.2", 7,
                "com.example.androidmcp", "0.6.2", 8,
                Set.of("aa"), Set.of("aa"));
    }

    @Test public void rejectsWrongPackageOrVersion() {
        assertThrows(IllegalArgumentException.class, () ->
                ApkIdentityValidation.requireValidCandidate(
                        "com.example.androidmcp", "0.6.2", 7,
                        "evil.example", "0.6.2", 8,
                        Set.of("aa"), Set.of("aa")));
        assertThrows(IllegalArgumentException.class, () ->
                ApkIdentityValidation.requireValidCandidate(
                        "com.example.androidmcp", "0.6.2", 7,
                        "com.example.androidmcp", "0.6.3", 8,
                        Set.of("aa"), Set.of("aa")));
    }

    @Test public void rejectsDowngradeOrSameVersionCode() {
        assertThrows(IllegalArgumentException.class, () ->
                ApkIdentityValidation.requireValidCandidate(
                        "com.example.androidmcp", "0.6.2", 7,
                        "com.example.androidmcp", "0.6.2", 7,
                        Set.of("aa"), Set.of("aa")));
        assertThrows(IllegalArgumentException.class, () ->
                ApkIdentityValidation.requireValidCandidate(
                        "com.example.androidmcp", "0.6.2", 7,
                        "com.example.androidmcp", "0.6.2", 6,
                        Set.of("aa"), Set.of("aa")));
    }

    @Test public void rejectsMissingOrDifferentSigners() {
        assertThrows(IllegalArgumentException.class, () ->
                ApkIdentityValidation.requireValidCandidate(
                        "com.example.androidmcp", "0.6.2", 7,
                        "com.example.androidmcp", "0.6.2", 8,
                        Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () ->
                ApkIdentityValidation.requireValidCandidate(
                        "com.example.androidmcp", "0.6.2", 7,
                        "com.example.androidmcp", "0.6.2", 8,
                        Set.of("aa"), Set.of("bb")));
    }
}
