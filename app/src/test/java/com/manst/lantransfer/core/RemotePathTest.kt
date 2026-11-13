package com.manst.lantransfer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePathTest {

    @Test
    fun normalize_handlesEmptyAndRoot() {
        assertEquals("/", RemotePath.normalize(null))
        assertEquals("/", RemotePath.normalize(""))
        assertEquals("/", RemotePath.normalize("   "))
        assertEquals("/", RemotePath.normalize("/"))
    }

    @Test
    fun normalize_collapsesSlashesAndDots() {
        assertEquals("/a/b", RemotePath.normalize("//a//b//"))
        assertEquals("/a/c", RemotePath.normalize("/a/./b/../c"))
        assertEquals("/", RemotePath.normalize("/.."))
        assertEquals("/a", RemotePath.normalize("a"))
        assertEquals("/x/y", RemotePath.normalize("x\\y"))
    }

    @Test
    fun join_combinesPaths() {
        assertEquals("/x", RemotePath.join("/", "x"))
        assertEquals("/a/b", RemotePath.join("/a", "b"))
        assertEquals("/a/b", RemotePath.join("/a/", "b"))
        assertEquals("/b", RemotePath.join("/a", "/b"))
        assertEquals("/a/b", RemotePath.join("/a/b", "."))
        assertEquals("/a", RemotePath.join("/a/b", ".."))
        assertEquals("/", RemotePath.join("/", ""))
    }

    @Test
    fun parent_walksUp() {
        assertNull(RemotePath.parent("/"))
        assertEquals("/", RemotePath.parent("/a"))
        assertEquals("/a", RemotePath.parent("/a/b"))
        assertEquals("/a", RemotePath.parent("/a/b/"))
    }

    @Test
    fun name_returnsLastSegment() {
        assertEquals("/", RemotePath.name("/"))
        assertEquals("b.txt", RemotePath.name("/a/b.txt"))
        assertEquals("a", RemotePath.name("/a/"))
    }

    @Test
    fun looksLikeDirectory_detectsTrailingSlash() {
        assertTrue(RemotePath.looksLikeDirectory(null))
        assertTrue(RemotePath.looksLikeDirectory(""))
        assertTrue(RemotePath.looksLikeDirectory("/"))
        assertTrue(RemotePath.looksLikeDirectory("/a/"))
        assertFalse(RemotePath.looksLikeDirectory("/a"))
    }
}