package com.mazevm.android.data

import com.mazevm.android.data.model.ImageReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Docker's shorthand is ambiguous by design: the same slash-separated string means a
 * different thing depending on whether the first segment looks like a hostname. Getting
 * it wrong sends the pull to the wrong registry, so the rules are pinned here.
 */
class ImageReferenceTest {

    @Test
    fun `a bare name is an official image on docker hub`() {
        val reference = ImageReference.parse("alpine")!!
        assertEquals("docker.io", reference.registry)
        assertEquals("library/alpine", reference.repository)
        assertEquals("latest", reference.tag)
    }

    @Test
    fun `a tag is taken from the last colon`() {
        val reference = ImageReference.parse("debian:bookworm-slim")!!
        assertEquals("library/debian", reference.repository)
        assertEquals("bookworm-slim", reference.tag)
    }

    @Test
    fun `one path segment is a docker hub user, not a host`() {
        val reference = ImageReference.parse("kalilinux/kali-rolling")!!
        assertEquals("docker.io", reference.registry)
        assertEquals("kalilinux/kali-rolling", reference.repository)
    }

    @Test
    fun `a first segment with a dot is a registry host`() {
        val reference = ImageReference.parse("ghcr.io/homebrew/core:latest")!!
        assertEquals("ghcr.io", reference.registry)
        assertEquals("homebrew/core", reference.repository)
        assertEquals("latest", reference.tag)
    }

    @Test
    fun `a host with a port is not mistaken for a tag`() {
        val reference = ImageReference.parse("localhost:5000/myimage:v2")!!
        assertEquals("localhost:5000", reference.registry)
        assertEquals("myimage", reference.repository)
        assertEquals("v2", reference.tag)
    }

    @Test
    fun `a digest suffix is dropped rather than treated as a tag`() {
        val reference = ImageReference.parse("alpine@sha256:abc123")!!
        assertEquals("library/alpine", reference.repository)
        assertEquals("latest", reference.tag)
    }

    @Test
    fun `the docker prefix is accepted`() {
        val reference = ImageReference.parse("docker://ubuntu:24.04")!!
        assertEquals("library/ubuntu", reference.repository)
        assertEquals("24.04", reference.tag)
    }

    @Test
    fun `blank input is rejected`() {
        assertNull(ImageReference.parse(""))
        assertNull(ImageReference.parse("   "))
        assertNull(ImageReference.parse(":"))
    }

    @Test
    fun `canonical form hides the defaults it added`() {
        assertEquals("alpine:latest", ImageReference.parse("alpine")!!.canonical)
        assertEquals(
            "kalilinux/kali-rolling:latest",
            ImageReference.parse("kalilinux/kali-rolling")!!.canonical,
        )
        assertEquals(
            "ghcr.io/owner/app:1.0",
            ImageReference.parse("ghcr.io/owner/app:1.0")!!.canonical,
        )
    }

    @Test
    fun `short name is what a card is titled with`() {
        assertEquals("alpine", ImageReference.parse("alpine")!!.shortName)
        assertEquals("kali-rolling", ImageReference.parse("kalilinux/kali-rolling")!!.shortName)
    }
}
