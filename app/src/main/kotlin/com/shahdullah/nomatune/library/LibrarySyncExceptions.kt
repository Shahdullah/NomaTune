/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Based on ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.library

/** Raised when a library sync is attempted with no usable YouTube login cookie. */
class LibraryLoginRequiredException : IllegalStateException("Library sync requires a signed-in account")

/** Raised when the user has turned YouTube Music library sync off in settings. */
class LibrarySyncDisabledException : IllegalStateException("Library sync is disabled in settings")
