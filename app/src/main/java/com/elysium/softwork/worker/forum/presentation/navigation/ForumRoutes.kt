package com.elysium.softwork.worker.forum.presentation.navigation

/**
 * Route catalog for the Forum bounded context. The feed route is also the bottom-nav
 * destination, so [FEED] is referenced from
 * `com.elysium.softwork.shared.presentation.navigation.MainNavHost` when wiring the "Forum"
 * tab.
 *
 * Extracted from `ForumNavigation.kt` so other navigation hosts can import the constants
 * without pulling in the `NavGraphBuilder` extension.
 */
object ForumRoutes {
    const val FEED: String = "forum/feed"

    /** Intermediate category-selection step opened before the composer. */
    const val CATEGORY_SELECTION: String = "forum/categories"

    private const val NEW_POST_BASE: String = "forum/new-post"
    const val NEW_POST_ARG_CATEGORY_ID: String = "categoryId"

    /** Composer route, parameterized by the chosen `category_id` (`LongType`). */
    const val NEW_POST: String = "$NEW_POST_BASE/{$NEW_POST_ARG_CATEGORY_ID}"

    /** Builds a concrete composer route under [categoryId]. */
    fun newPost(categoryId: Long): String = "$NEW_POST_BASE/$categoryId"

    private const val THREAD_BASE: String = "forum/thread"
    const val THREAD_ARG_THREAD_ID: String = "threadId"
    const val THREAD: String = "$THREAD_BASE/{$THREAD_ARG_THREAD_ID}"

    /** Builds a concrete thread route for the given [threadId]. */
    fun thread(threadId: Long): String = "$THREAD_BASE/$threadId"

    private const val REPORT_BASE: String = "forum/report"
    const val REPORT_ARG_THREAD_ID: String = "threadId"
    const val REPORT: String = "$REPORT_BASE/{$REPORT_ARG_THREAD_ID}"

    /** Builds a concrete report route for the given [threadId]. */
    fun report(threadId: Long): String = "$REPORT_BASE/$threadId"

    /** Read-only list of the user's submitted reports + their current status. */
    const val REPORTS_STATUS: String = "forum/reports-status"
}
