package com.github.k1rakishou.chan.core.manager

import androidx.annotation.GuardedBy
import com.github.k1rakishou.chan.core.base.DebouncingCoroutineExecutor
import com.github.k1rakishou.chan.core.base.SerializedCoroutineExecutor
import com.github.k1rakishou.chan.core.site.SiteRegistry
import com.github.k1rakishou.common.DoNotStrip
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.common.SuspendableInitializer
import com.github.k1rakishou.common.mutableListWithCap
import com.github.k1rakishou.common.mutableMapWithCap
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.bookmark.ThreadBookmark
import com.github.k1rakishou.model.data.bookmark.ThreadBookmarkView
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.repository.BookmarksRepository
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.processors.PublishProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import org.joda.time.DateTime
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

class BookmarksManager(
  private val isDevFlavor: Boolean,
  private val verboseLogsEnabled: Boolean,
  private val appScope: CoroutineScope,
  private val applicationVisibilityManager: ApplicationVisibilityManager,
  private val archivesManager: ArchivesManager,
  private val bookmarksRepository: BookmarksRepository,
  private val siteRegistry: SiteRegistry,
  private val currentOpenedDescriptorStateManager: CurrentOpenedDescriptorStateManager
) {
  private val lock = ReentrantReadWriteLock()
  private val bookmarksChangeFlow = MutableSharedFlow<BookmarkChange>(extraBufferCapacity = 128)
  private val threadIsFetchingEventsSubject = PublishProcessor.create<ChanDescriptor.ThreadDescriptor>()

  private val persistBookmarksExecutor = SerializedCoroutineExecutor(appScope)
  private val delayedBookmarksChangedExecutor = DebouncingCoroutineExecutor(appScope)
  private val suspendableInitializer = SuspendableInitializer<Unit>("BookmarksManager")

  @GuardedBy("lock")
  private val bookmarks = mutableMapWithCap<ChanDescriptor.ThreadDescriptor, ThreadBookmark>(256)

  fun initialize() {
    Logger.d(TAG, "BookmarksManager.initialize()")
    startListeningForAppVisibilityUpdates()

    val allSiteNames = siteRegistry.SITE_CLASSES_MAP.keys
      .map { siteDescriptor -> siteDescriptor.siteName }
      .toSet()

    appScope.launch(Dispatchers.IO) {
      Logger.d(TAG, "initializeBookmarksInternal() start")
      initializeBookmarksInternal(allSiteNames)
      Logger.d(TAG, "initializeBookmarksInternal() end")
    }
  }

  private fun startListeningForAppVisibilityUpdates() {
    applicationVisibilityManager.addListener { visibility ->
      if (!suspendableInitializer.isInitialized()) {
        return@addListener
      }

      if (visibility != ApplicationVisibility.Background) {
        return@addListener
      }

      persistBookmarks(eager = true)
    }
  }

  private suspend fun initializeBookmarksInternal(allSiteNames: Set<String>) {
    when (val bookmarksResult = bookmarksRepository.initialize(allSiteNames)) {
      is ModularResult.Value -> {
        lock.write {
          bookmarks.clear()

          bookmarksResult.value.forEach { threadBookmark ->
            bookmarks[threadBookmark.threadDescriptor] = threadBookmark
          }
        }

        suspendableInitializer.initWithValue(Unit)
        Logger.d(TAG, "initializeBookmarksInternal() done. Loaded ${bookmarks.size} bookmarks and " +
          "${activeBookmarksCount()} active bookmarks")
      }
      is ModularResult.Error -> {
        suspendableInitializer.initWithError(bookmarksResult.error)
        Logger.e(TAG, "initializeBookmarksInternal() error", bookmarksResult.error)
      }
    }

    bookmarksChanged(BookmarkChange.BookmarksInitialized)
  }

  fun listenForBookmarksChanges(): SharedFlow<BookmarkChange> {
    return bookmarksChangeFlow.asSharedFlow()
  }

  fun listenForFetchEventsFromActiveThreads(): Flowable<ChanDescriptor.ThreadDescriptor> {
    return threadIsFetchingEventsSubject
      .onBackpressureLatest()
      .observeOn(AndroidSchedulers.mainThread())
      .doOnError { error -> Logger.e(TAG, "listenForFetchEventsFromActiveThreads error", error) }
      .hide()
  }

  @OptIn(ExperimentalTime::class)
  suspend fun awaitUntilInitialized() {
    if (isReady()) {
      return
    }

    Logger.d(TAG, "BookmarksManager is not ready yet, waiting...")
    val duration = measureTime { suspendableInitializer.awaitUntilInitialized() }
    Logger.d(TAG, "BookmarksManager initialization completed, took $duration")
  }

  fun isReady() = suspendableInitializer.isInitialized()

  fun exists(threadDescriptor: ChanDescriptor.ThreadDescriptor): Boolean {
    return lock.read { bookmarks.containsKey(threadDescriptor) }
  }

  /**
   * Called by [ChanThreadManager] when we are about to start loading thread posts from the server.
   * We use this to update a bookmark associated with this thread (if it exists and is active).
   * */
  fun onThreadIsFetchingData(threadDescriptor: ChanDescriptor.ThreadDescriptor) {
    if (threadDescriptor == currentOpenedDescriptorStateManager.currentThreadDescriptor) {
      val isActive = lock.read { bookmarks[threadDescriptor]?.isActive() ?: false }
      if (isActive) {
        synchronized(this) {
          threadIsFetchingEventsSubject.onNext(threadDescriptor)
        }
      }
    }
  }

  @JvmOverloads
  fun createBookmark(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    title: String? = null,
    thumbnailUrl: HttpUrl? = null
  ): Boolean {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    val bookmarkToCreate = SimpleThreadBookmark(
      threadDescriptor = threadDescriptor,
      title = title,
      thumbnailUrl = thumbnailUrl
    )

    return createBookmarks(listOf(bookmarkToCreate))
  }

  /**
   * Creates bookmarks in the memory and schedules a persist task that should be executed some time
   * in the near future.
   * */
  fun createBookmarks(simpleThreadBookmarkList: List<SimpleThreadBookmark>): Boolean {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    val actuallyCreated = createBookmarksInternal(simpleThreadBookmarkList)
    if (actuallyCreated.isEmpty()) {
      return false
    }

    bookmarksChanged(BookmarkChange.BookmarksCreated(actuallyCreated))
    Logger.d(TAG, "Bookmarks created (${actuallyCreated.size})")
    return true
  }

  /**
   * Creates bookmarks and persist them right away.
   * */
  suspend fun createBookmarksSuspend(simpleThreadBookmarkList: List<SimpleThreadBookmark>): Boolean {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    val actuallyCreated = createBookmarksInternal(simpleThreadBookmarkList)
    if (actuallyCreated.isEmpty()) {
      return false
    }

    persistBookmarksInternal()
    bookmarksChangeFlow.emit(BookmarkChange.BookmarksCreated(actuallyCreated))

    Logger.d(TAG, "Bookmarks created (${actuallyCreated.size})")
    return true
  }

  private fun createBookmarksInternal(
    simpleThreadBookmarkList: List<SimpleThreadBookmark>
  ): List<ChanDescriptor.ThreadDescriptor> {
    if (simpleThreadBookmarkList.isEmpty()) {
      return emptyList()
    }

    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    val actuallyCreated = mutableListOf<ChanDescriptor.ThreadDescriptor>()

    lock.write {
      simpleThreadBookmarkList.forEach { simpleThreadBookmark ->
        val threadDescriptor = simpleThreadBookmark.threadDescriptor
        val title = simpleThreadBookmark.title
        val thumbnailUrl = simpleThreadBookmark.thumbnailUrl
        val initialFlags = simpleThreadBookmark.initialFlags

        if (bookmarks.containsKey(threadDescriptor)) {
          return@forEach
        }

        val threadBookmark = ThreadBookmark.create(
          threadDescriptor = threadDescriptor,
          createdOn = DateTime.now(),
          initialFlags = initialFlags
        ).apply {
          this.title = title
          this.thumbnailUrl = thumbnailUrl
        }

        actuallyCreated += threadDescriptor
        bookmarks[threadDescriptor] = threadBookmark
      }
    }

    return actuallyCreated
  }

  fun deleteBookmark(threadDescriptor: ChanDescriptor.ThreadDescriptor): Boolean {
    return deleteBookmarks(listOf(threadDescriptor))
  }

  fun deleteBookmarks(threadDescriptors: List<ChanDescriptor.ThreadDescriptor>): Boolean {
    require(threadDescriptors.isNotEmpty()) { "threadDescriptors is empty!" }
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    val updated = lock.write {
      var updated = false

      for (threadDescriptor in threadDescriptors) {
        if (!bookmarks.containsKey(threadDescriptor)) {
          continue
        }

        bookmarks.remove(threadDescriptor)
        updated = true
      }

      return@write updated
    }

    if (!updated) {
      return false
    }

    bookmarksChanged(BookmarkChange.BookmarksDeleted(threadDescriptors))
    Logger.d(TAG, "Bookmarks deleted count ${threadDescriptors.size}")

    return true
  }

  fun getBookmarkThumbnailByThreadId(threadDescriptor: ChanDescriptor.ThreadDescriptor): HttpUrl? {
    return lock.read { bookmarks[threadDescriptor]?.thumbnailUrl }
  }

  /**
   * @return either a [ChanDescriptor.ThreadDescriptor] of this bookmark if this bookmark
   * was actually updated (something was changed) or null if [mutator] returned the same bookmark.
   * Don't forget to call [persistBookmarkManually] after this method!
   * */
  fun updateBookmarkNoPersist(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    mutator: (ThreadBookmark) -> Unit
  ): ChanDescriptor.ThreadDescriptor? {
    return updateBookmarksNoPersist(listOf(threadDescriptor), mutator).firstOrNull()
  }

  /**
   * @return either a list of [ChanDescriptor.ThreadDescriptor] of bookmarks that were actually changed
   * by [mutator] or empty list if no bookmarks were changed.
   * Don't forget to call [persistBookmarksManually] after this method!
   * */
  fun updateBookmarksNoPersist(
    threadDescriptors: Collection<ChanDescriptor.ThreadDescriptor>,
    mutator: (ThreadBookmark) -> Unit
  ): Set<ChanDescriptor.ThreadDescriptor> {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }
    require(threadDescriptors.isNotEmpty()) { "threadDescriptors is empty!" }

    if (threadDescriptors.isEmpty()) {
      return emptySet()
    }

    val updatedBookmarks = mutableSetOf<ChanDescriptor.ThreadDescriptor>()

    lock.write {
      threadDescriptors.forEach { threadDescriptor ->
        val oldThreadBookmark = bookmarks[threadDescriptor]
          ?: return@forEach

        val mutatedBookmark = oldThreadBookmark.deepCopy()
        mutator(mutatedBookmark)

        if (oldThreadBookmark != mutatedBookmark) {
          bookmarks[threadDescriptor] = mutatedBookmark
          updatedBookmarks += threadDescriptor
        }
      }

      return@write
    }

    if (updatedBookmarks.isNotEmpty()) {
      bookmarksChangeFlow.tryEmit(BookmarkChange.BookmarksUpdated(updatedBookmarks))
    }

    return updatedBookmarks
  }

  fun pruneNonActive() {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    val toDelete = mutableListWithCap<ChanDescriptor.ThreadDescriptor>(bookmarks.size / 2)

    lock.write {
      bookmarks.entries.forEach { (threadDescriptor, threadBookmark) ->
        if (!threadBookmark.isActive()) {
          toDelete += threadDescriptor
        }
      }

      if (toDelete.size > 0) {
        toDelete.forEach { threadDescriptor ->
          bookmarks.remove(threadDescriptor)
        }
      }
    }

    if (toDelete.isNotEmpty()) {
      bookmarksChanged(BookmarkChange.BookmarksDeleted(toDelete))
    }
  }

  fun deleteAllBookmarks() {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    val allBookmarksDescriptors = mutableListOf<ChanDescriptor.ThreadDescriptor>()

    lock.write {
      allBookmarksDescriptors.addAll(bookmarks.keys.toList())
      bookmarks.clear()
    }

    persistBookmarksExecutor.post {
      bookmarksRepository.deleteAll().safeUnwrap { error ->
        Logger.e(TAG, "deleteAllBookmarks() bookmarksRepository.deleteAll() error", error)
        return@post
      }

      bookmarksChangeFlow.emit(BookmarkChange.BookmarksDeleted(allBookmarksDescriptors))
    }
  }

  fun readPostsAndNotificationsForThread(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    lastPostInThreadNo: Long?
  ) {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    lock.write {
      val threadBookmark = bookmarks[threadDescriptor]
        ?: return@write

      threadBookmark.readAllPostsAndNotifications()

      if (lastPostInThreadNo != null) {
        threadBookmark.updateLastViewedPostNo(lastPostInThreadNo)
      }

      bookmarksChanged(BookmarkChange.BookmarksUpdated(listOf(threadDescriptor)))
    }
  }

  fun readAllPostsAndNotifications() {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    lock.write {
      if (bookmarks.keys.isEmpty()) {
        return@write
      }

      bookmarks.entries.forEach { (_, threadBookmark) ->
        threadBookmark.readAllPostsAndNotifications()
      }

      bookmarksChanged(BookmarkChange.BookmarksUpdated(bookmarks.keys))
    }
  }

  fun viewBookmark(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    viewer: (ThreadBookmarkView) -> Unit
  ) {
    viewBookmarks(listOf(threadDescriptor), viewer)
  }

  fun viewBookmarks(
    threadDescriptors: Collection<ChanDescriptor.ThreadDescriptor>,
    viewer: (ThreadBookmarkView) -> Unit
  ) {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }
    require(threadDescriptors.isNotEmpty()) { "threadDescriptors is empty!" }

    lock.read {
      threadDescriptors.forEach { threadDescriptor ->
        if (!bookmarks.containsKey(threadDescriptor)) {
          return@forEach
        }

        val threadBookmark = bookmarks[threadDescriptor]
          ?: return@forEach

        viewer(ThreadBookmarkView.fromThreadBookmark(threadBookmark))
      }
    }
  }

  fun <T> mapBookmark(threadDescriptor: ChanDescriptor.ThreadDescriptor, mapper: (ThreadBookmarkView) -> T): T? {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      if (!bookmarks.containsKey(threadDescriptor)) {
        return@read null
      }

      val threadBookmark = bookmarks[threadDescriptor]
        ?: return@read null

      return@read mapper(ThreadBookmarkView.fromThreadBookmark(threadBookmark))
    }
  }

  fun <T> mapBookmarks(
    threadDescriptors: Collection<ChanDescriptor.ThreadDescriptor>,
    mapper: (ThreadBookmarkView) -> T
  ): List<T> {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }
    require(threadDescriptors.isNotEmpty()) { "threadDescriptors is empty!" }

    return lock.read {
      return@read threadDescriptors.map { threadDescriptor ->
        val threadBookmark = checkNotNull(bookmarks[threadDescriptor]) {
          "Bookmarks do not contain ${threadDescriptor}"
        }

        return@map mapper(ThreadBookmarkView.fromThreadBookmark(threadBookmark))
      }
    }
  }

  fun <T> mapAllBookmarks(mapper: (ThreadBookmarkView) -> T): List<T> {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      return@read bookmarks.keys.map { threadDescriptor ->
        val threadBookmark = checkNotNull(bookmarks[threadDescriptor]) {
          "Bookmarks do not contain ${threadDescriptor}"
        }

        return@map mapper(ThreadBookmarkView.fromThreadBookmark(threadBookmark))
      }
    }
  }

  fun <T> mapNotNullAllBookmarks(mapper: (ThreadBookmarkView) -> T?): List<T> {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      return@read bookmarks.keys.mapNotNull { threadDescriptor ->
        val threadBookmark = checkNotNull(bookmarks[threadDescriptor]) {
          "Bookmarks do not contain ${threadDescriptor}"
        }

        return@mapNotNull mapper(ThreadBookmarkView.fromThreadBookmark(threadBookmark))
      }
    }
  }

  fun bookmarksCount(): Int {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      return@read bookmarks.size
    }
  }

  fun activeBookmarksCount(): Int {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      return@read bookmarks.values.count { threadBookmark -> activeBookmarkPredicate(threadBookmark) }
    }
  }

  fun hasActiveBookmarks(): Boolean {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      return@read bookmarks.any { (_, threadBookmark) -> activeBookmarkPredicate(threadBookmark) }
    }
  }

  fun getTotalUnseenPostsCount(): Int {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      return@read bookmarks.values.sumBy { threadBookmark -> threadBookmark.unseenPostsCount() }
    }
  }

  fun hasUnreadReplies(): Boolean {
    check(isReady()) { "BookmarksManager is not ready yet! Use awaitUntilInitialized()" }

    return lock.read {
      return@read bookmarks.values.any { threadBookmark -> threadBookmark.hasUnreadReplies() }
    }
  }

  fun onPostViewed(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    postNo: Long,
    unseenPostsCount: Int
  ) {
    if (!isReady()) {
      return
    }

    val lastViewedPostNo = lock.read {
      if (!bookmarks.containsKey(threadDescriptor)) {
        return
      }

      return@read bookmarks[threadDescriptor]?.lastViewedPostNo ?: 0L
    }

    if (postNo <= lastViewedPostNo) {
      return
    }

    updateBookmarkNoPersist(threadDescriptor) { threadBookmark ->
      threadBookmark.updateSeenPostsCount(unseenPostsCount)
      threadBookmark.updateLastViewedPostNo(postNo)
      threadBookmark.readRepliesUpTo(postNo)
    }

    delayedBookmarksChangedExecutor.post(
      timeout = 250L,
      func = {
        persistBookmarks(
          eager = false,
          onBookmarksPersisted = {
            val bookmarkChange = BookmarkChange.BookmarksUpdated(listOf(threadDescriptor))
            bookmarksChangeFlow.emit(bookmarkChange)
          })
      }
    )
  }

  fun refreshBookmarks() {
    bookmarksChanged(BookmarkChange.BookmarksUpdated(null))
  }

  suspend fun persistBookmarkManually(threadDescriptor: ChanDescriptor.ThreadDescriptor) {
    persistBookmarksManually(listOf(threadDescriptor))
  }

  suspend fun persistBookmarksManually(threadDescriptors: Collection<ChanDescriptor.ThreadDescriptor>) {
    if (threadDescriptors.isEmpty()) {
      return
    }

    Logger.d(TAG, "persistBookmarksManually() persistBookmarksInternal called")
    persistBookmarksInternal()
    Logger.d(TAG, "persistBookmarksManually() persistBookmarksInternal finished")

    val bookmarkChange = BookmarkChange.BookmarksUpdated(threadDescriptors)
    bookmarksChangeFlow.emit(bookmarkChange)
  }

  private fun bookmarksChanged(bookmarkChange: BookmarkChange) {
    when (bookmarkChange) {
      BookmarkChange.BookmarksInitialized,
      is BookmarkChange.BookmarksUpdated -> {
        // no-op
      }
      is BookmarkChange.BookmarksCreated -> {
        check(bookmarkChange.threadDescriptors.isNotEmpty()) { "threadDescriptors is empty!" }
      }
      is BookmarkChange.BookmarksDeleted -> {
        check(bookmarkChange.threadDescriptors.isNotEmpty()) { "threadDescriptors is empty!" }
      }
    }

    persistBookmarks(
      eager = false,
      onBookmarksPersisted = {
        // Only notify the listeners about bookmark updates AFTER we have persisted them
        bookmarksChangeFlow.emit(bookmarkChange)

        Logger.d(TAG, "subscriptions=${bookmarksChangeFlow.subscriptionCount.value}")
      }
    )
  }

  fun enqueuePersistFunc(func: suspend () -> Unit) {
    persistBookmarksExecutor.post {
      try {
        func()
      } catch (error: Throwable) {
        Logger.e(TAG, "enqueuePersistCall() error", error)
      }
    }
  }

  private fun persistBookmarks(
    eager: Boolean = false,
    onBookmarksPersisted: (suspend () -> Unit)? = null
  ) {
    if (!isReady()) {
      return
    }

    if (eager) {
      appScope.launch(Dispatchers.Default) {
        Logger.d(TAG, "persistBookmarks eager called")
        persistBookmarksInternal()
        onBookmarksPersisted?.invoke()
        Logger.d(TAG, "persistBookmarks eager finished")
      }
    } else {
      persistBookmarksExecutor.post {
        Logger.d(TAG, "persistBookmarks async called")
        persistBookmarksInternal()
        onBookmarksPersisted?.invoke()
        Logger.d(TAG, "persistBookmarks async finished")
      }
    }
  }

  private suspend fun persistBookmarksInternal() {
    bookmarksRepository.persist(getAllBookmarks())
      .safeUnwrap { error ->
        Logger.e(TAG, "Failed to persist bookmarks", error)
        return
      }
  }

  private fun getAllBookmarks(): List<ThreadBookmark> {
    return lock.read { bookmarks.values.map { bookmark -> bookmark.deepCopy() } }
  }

  private fun activeBookmarkPredicate(threadBookmark: ThreadBookmark): Boolean {
    val siteDescriptor = threadBookmark.threadDescriptor.siteDescriptor()
    val isArchiveBookmark = archivesManager.isSiteArchive(siteDescriptor)

    return !isArchiveBookmark && threadBookmark.isActive()
  }

  data class SimpleThreadBookmark(
    val threadDescriptor: ChanDescriptor.ThreadDescriptor,
    val title: String? = null,
    val thumbnailUrl: HttpUrl? = null,
    val initialFlags: BitSet? = null
  )

  @DoNotStrip
  sealed class BookmarkChange {
    object BookmarksInitialized : BookmarkChange()

    class BookmarksCreated(val threadDescriptors: Collection<ChanDescriptor.ThreadDescriptor>) : BookmarkChange() {
      init {
        require(threadDescriptors.isNotEmpty()) { "threadDescriptors must not be empty!" }
      }
    }
    class BookmarksDeleted(val threadDescriptors: Collection<ChanDescriptor.ThreadDescriptor>) : BookmarkChange() {
      init {
        require(threadDescriptors.isNotEmpty()) { "threadDescriptors must not be empty!" }
      }
    }

    // When threadDescriptors is null that means that we want to update ALL bookmarks. For now we
    // only use it in one place - when opening BookmarksController to refresh all bookmarks.
    class BookmarksUpdated(val threadDescriptors: Collection<ChanDescriptor.ThreadDescriptor>?) : BookmarkChange() {
      init {
        if (threadDescriptors != null) {
          require(threadDescriptors.isNotEmpty()) { "threadDescriptors must not be empty!" }
        }
      }
    }

    fun threadDescriptors(): Collection<ChanDescriptor.ThreadDescriptor> {
      return when (this) {
        BookmarksInitialized -> emptyList()
        is BookmarksCreated -> threadDescriptors
        is BookmarksDeleted -> threadDescriptors
        is BookmarksUpdated -> threadDescriptors ?: emptyList()
      }
    }

    fun threadDescriptorsOrNull(): Collection<ChanDescriptor.ThreadDescriptor>? {
      return when (this) {
        BookmarksInitialized -> null
        is BookmarksCreated -> threadDescriptors
        is BookmarksDeleted -> threadDescriptors
        is BookmarksUpdated -> threadDescriptors
      }
    }

  }

  companion object {
    private const val TAG = "BookmarksManager"
  }
}
