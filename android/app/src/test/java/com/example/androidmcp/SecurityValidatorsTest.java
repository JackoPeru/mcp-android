package com.example.androidmcp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SecurityValidatorsTest {
    @Test public void jsonNestingIsBoundedBeforeRecursiveParsing() {
        assertTrue(SecurityValidators.isBoundedJson("{\"params\":{\"text\":\"[{}]\\\"\"}}"));
        assertFalse(SecurityValidators.isBoundedJson("[".repeat(17) + "]".repeat(17)));
        assertFalse(SecurityValidators.isBoundedJson("{\"text\":\"unterminated}"));
        assertFalse(SecurityValidators.isBoundedJson("{'text':'nonstandard'}"));
    }
    @Test
    public void tokenMustBeExactlyLowercaseHex64() {
        assertTrue(SecurityValidators.isValidToken("a".repeat(64)));
        assertTrue(SecurityValidators.isValidToken("f".repeat(64)));
        assertFalse(SecurityValidators.isValidToken("a".repeat(63)));
        assertFalse(SecurityValidators.isValidToken("A".repeat(64)));
        assertFalse(SecurityValidators.isValidToken(null));
    }

    @Test
    public void relativePathsRejectTraversalAndUris() {
        assertTrue(SecurityValidators.isValidRelativePath(""));
        assertTrue(SecurityValidators.isValidRelativePath("docs/notes.txt"));
        assertFalse(SecurityValidators.isValidRelativePath("."));
        assertFalse(SecurityValidators.isValidRelativePath("docs/../secret"));
        assertFalse(SecurityValidators.isValidRelativePath("docs\\secret"));
        assertFalse(SecurityValidators.isValidRelativePath("/etc/passwd"));
        assertFalse(SecurityValidators.isValidRelativePath("content://arbitrary"));
        assertFalse(SecurityValidators.isValidRelativePath("docs//secret"));
    }

    @Test
    public void fileRangesAndPagesStayBounded() {
        assertTrue(SecurityValidators.isValidReadRange(0L, 0L));
        assertTrue(SecurityValidators.isValidReadRange(0L, 262144L));
        assertFalse(SecurityValidators.isValidReadRange(0L, 262145L));
        assertFalse(SecurityValidators.isValidReadRange(-1L, 1L));
        assertFalse(SecurityValidators.isValidReadRange(Long.MAX_VALUE, 1L));
        assertTrue(SecurityValidators.isValidPage(0L, 200));
        assertFalse(SecurityValidators.isValidPage(-1L, 1));
        assertFalse(SecurityValidators.isValidPage(0L, 201));
        assertTrue(SecurityValidators.isValidWriteRange(0L, 262144L));
        assertFalse(SecurityValidators.isValidWriteRange(0L, 262145L));
        assertTrue(SecurityValidators.isValidFileName("notes.txt"));
        assertFalse(SecurityValidators.isValidFileName("../notes.txt"));
        assertFalse(SecurityValidators.isValidFileName("a/b"));
    }

    @Test
    public void onlyTailscaleCarrierIpv4IsBindable() {
        assertTrue(SecurityValidators.isTailscaleIpv4("100.64.0.1"));
        assertTrue(SecurityValidators.isTailscaleIpv4("100.127.255.254"));
        assertFalse(SecurityValidators.isTailscaleIpv4("100.63.255.255"));
        assertFalse(SecurityValidators.isTailscaleIpv4("100.128.0.1"));
        assertFalse(SecurityValidators.isTailscaleIpv4("100.64.0.1:8765"));
        assertFalse(SecurityValidators.isTailscaleIpv4("fd7a:115c:a1e0::1"));
    }
}
