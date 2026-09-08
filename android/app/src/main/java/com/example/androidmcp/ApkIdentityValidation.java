package com.example.androidmcp;

import java.util.Set;

/** Pure validation rules for a downloaded APK before handing it to PackageInstaller. */
public final class ApkIdentityValidation {
    private ApkIdentityValidation() { }

    public static void requireValidCandidate(
            String expectedPackage,
            String expectedVersion,
            long currentVersionCode,
            String candidatePackage,
            String candidateVersion,
            long candidateVersionCode,
            Set<String> currentSignerDigests,
            Set<String> candidateSignerDigests) {
        if (expectedPackage == null || expectedPackage.isEmpty()
                || !expectedPackage.equals(candidatePackage)) {
            throw new IllegalArgumentException("APK package mismatch");
        }
        if (expectedVersion == null || expectedVersion.isEmpty()
                || !expectedVersion.equals(candidateVersion)) {
            throw new IllegalArgumentException("APK version mismatch");
        }
        if (candidateVersionCode <= currentVersionCode) {
            throw new IllegalArgumentException("APK versionCode must increase");
        }
        if (currentSignerDigests == null || candidateSignerDigests == null
                || currentSignerDigests.isEmpty() || candidateSignerDigests.isEmpty()
                || !currentSignerDigests.equals(candidateSignerDigests)) {
            throw new IllegalArgumentException("APK signer mismatch");
        }
    }
}
