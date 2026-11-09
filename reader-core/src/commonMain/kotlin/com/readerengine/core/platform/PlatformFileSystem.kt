package com.readerengine.core.platform

import okio.FileSystem

/**
 * Platform default [FileSystem], providing access to the standard host file system
 * across Android, JVM, and Apple/Native platforms.
 */
public expect val defaultFileSystem: FileSystem
