package com.github.k1rakishou.chan.core.site.sites.chan4

import android.webkit.CookieManager
import android.webkit.WebView
import com.github.k1rakishou.OptionSettingItem
import com.github.k1rakishou.Setting
import com.github.k1rakishou.chan.core.net.JsonReaderRequest
import com.github.k1rakishou.chan.core.site.ChunkDownloaderSiteProperties
import com.github.k1rakishou.chan.core.site.ResolvedChanDescriptor
import com.github.k1rakishou.chan.core.site.Site
import com.github.k1rakishou.chan.core.site.SiteActions
import com.github.k1rakishou.chan.core.site.SiteAuthentication
import com.github.k1rakishou.chan.core.site.SiteBase
import com.github.k1rakishou.chan.core.site.SiteEndpoints
import com.github.k1rakishou.chan.core.site.SiteIcon
import com.github.k1rakishou.chan.core.site.SiteRequestModifier
import com.github.k1rakishou.chan.core.site.SiteSetting
import com.github.k1rakishou.chan.core.site.SiteSetting.SiteOptionsSetting
import com.github.k1rakishou.chan.core.site.SiteUrlHandler
import com.github.k1rakishou.chan.core.site.common.FutabaChanReader
import com.github.k1rakishou.chan.core.site.http.DeleteRequest
import com.github.k1rakishou.chan.core.site.http.HttpCall
import com.github.k1rakishou.chan.core.site.http.login.AbstractLoginRequest
import com.github.k1rakishou.chan.core.site.http.login.Chan4LoginRequest
import com.github.k1rakishou.chan.core.site.http.login.Chan4LoginResponse
import com.github.k1rakishou.chan.core.site.limitations.ConstantAttachablesCount
import com.github.k1rakishou.chan.core.site.limitations.PasscodeDependantMaxAttachablesTotalSize
import com.github.k1rakishou.chan.core.site.limitations.SitePostingLimitationInfo
import com.github.k1rakishou.chan.core.site.parser.ChanReader
import com.github.k1rakishou.chan.core.site.parser.CommentParserType
import com.github.k1rakishou.chan.core.site.sites.archive.NativeArchivePost
import com.github.k1rakishou.chan.core.site.sites.search.Chan4SearchParams
import com.github.k1rakishou.chan.core.site.sites.search.SearchParams
import com.github.k1rakishou.chan.core.site.sites.search.SearchResult
import com.github.k1rakishou.chan.core.site.sites.search.SiteGlobalSearchType
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.common.DoNotStrip
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.common.errorMessageOrClassName
import com.github.k1rakishou.model.data.board.ChanBoard
import com.github.k1rakishou.model.data.board.pages.BoardPages
import com.github.k1rakishou.model.data.descriptor.BoardDescriptor
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.descriptor.SiteDescriptor
import com.github.k1rakishou.model.data.post.ChanPost
import com.github.k1rakishou.model.data.site.SiteBoards
import com.github.k1rakishou.persist_state.ReplyMode
import com.github.k1rakishou.prefs.OptionsSetting
import com.github.k1rakishou.prefs.StringSetting
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.*

@Suppress("PropertyName")
@DoNotStrip
open class Chan4 : SiteBase() {

  private val TAG = "Chan4"
  private val CAPTCHA_KEY = "6Ldp2bsSAAAAAAJ5uyx_lx34lJeEpTLVkP5k04qc"

  private lateinit var chunkDownloaderSiteProperties: ChunkDownloaderSiteProperties
  private lateinit var passUser: StringSetting
  private lateinit var passPass: StringSetting
  private lateinit var passToken: StringSetting
  private lateinit var captchaType: OptionsSetting<CaptchaType>
  lateinit var lastUsedFlagPerBoard: StringSetting

  private val siteRequestModifier by lazy { Chan4SiteRequestModifier(this, appConstants) }

  override val canCreateBoardsManually: Boolean
    get() = true

  override fun initialize() {
    super.initialize()

    passUser = StringSetting(prefs, "preference_pass_token", "")
    passPass = StringSetting(prefs, "preference_pass_pin", "")
    passToken = StringSetting(prefs, "preference_pass_id", "")

    captchaType = OptionsSetting(
      prefs,
      "preference_captcha_type_chan4",
      CaptchaType::class.java,
      CaptchaType.V2NOJS
    )

    lastUsedFlagPerBoard = StringSetting(
      prefs,
      "preference_flag_chan4",
      "0"
    )

    chunkDownloaderSiteProperties = ChunkDownloaderSiteProperties(
      enabled = true,
      siteSendsCorrectFileSizeInBytes = true
    )
  }

  private val endpoints: SiteEndpoints = object : SiteEndpoints {
    private val a = HttpUrl.Builder().scheme("https").host("a.4cdn.org").build()
    private val i = HttpUrl.Builder().scheme("https").host("i.4cdn.org").build()
    private val t = HttpUrl.Builder().scheme("https").host("i.4cdn.org").build()
    private val s = HttpUrl.Builder().scheme("https").host("s.4cdn.org").build()
    private val sys = HttpUrl.Builder().scheme("https").host("sys.4chan.org").build()
    private val b = HttpUrl.Builder().scheme("https").host("boards.4chan.org").build()
    private val search = HttpUrl.Builder().scheme("https").host("find.4chan.org").build()

    override fun catalog(boardDescriptor: BoardDescriptor): HttpUrl {
      return a.newBuilder()
        .addPathSegment(boardDescriptor.boardCode)
        .addPathSegment("catalog.json")
        .build()
    }

    override fun thread(threadDescriptor: ChanDescriptor.ThreadDescriptor): HttpUrl {
      return a.newBuilder()
        .addPathSegment(threadDescriptor.boardCode())
        .addPathSegment("thread")
        .addPathSegment(threadDescriptor.threadNo.toString() + ".json")
        .build()
    }

    override fun imageUrl(boardDescriptor: BoardDescriptor, arg: Map<String, String>): HttpUrl {
      val imageFile = arg["tim"].toString() + "." + arg["ext"]

      return i.newBuilder()
        .addPathSegment(boardDescriptor.boardCode)
        .addPathSegment(imageFile)
        .build()
    }

    override fun thumbnailUrl(
      boardDescriptor: BoardDescriptor,
      spoiler: Boolean,
      customSpoilers: Int,
      arg: Map<String, String>
    ): HttpUrl {
      val boardCode = boardDescriptor.boardCode

      return if (spoiler) {
        val image = s.newBuilder().addPathSegment("image")
        if (customSpoilers >= 0) {
          val i = secureRandom.nextInt(customSpoilers) + 1
          image.addPathSegment("spoiler-${boardCode}$i.png")
        } else {
          image.addPathSegment("spoiler.png")
        }

        image.build()
      } else {
        when (arg["ext"]) {
          "swf" -> (AppConstants.RESOURCES_ENDPOINT + "swf_thumb.png").toHttpUrl()
          else -> t.newBuilder()
            .addPathSegment(boardCode)
            .addPathSegment(arg["tim"].toString() + "s.jpg")
            .build()
        }
      }
    }

    override fun icon(icon: String, arg: Map<String, String>?): HttpUrl? {
      val b = s.newBuilder().addPathSegment("image")

      when (icon) {
        "country" -> {
          val countryCode = requireNotNull(arg?.get("country_code")) { "Bad arg map: $arg" }

          b.addPathSegment("country")
          b.addPathSegment(countryCode.toLowerCase(Locale.ENGLISH) + ".gif")
        }
        // https://s.4cdn.org/image/flags/[board]/[code].gif
        "board_flag" -> {
          val boardFlagCode = requireNotNull(arg?.get("board_flag_code")) { "Bad arg map: $arg" }
          val boardCode = requireNotNull(arg?.get("board_code")) { "Bad arg map: $arg" }

          b.addPathSegment("flags")
          b.addPathSegment(boardCode)
          b.addPathSegment(boardFlagCode.toLowerCase(Locale.ENGLISH) + ".gif")
        }
        "since4pass" -> b.addPathSegment("minileaf.gif")
      }

      return b.build()
    }

    override fun boards(): HttpUrl {
      return a.newBuilder().addPathSegment("boards.json").build()
    }

    override fun pages(board: ChanBoard): HttpUrl {
      return a.newBuilder()
        .addPathSegment(board.boardCode())
        .addPathSegment("threads.json")
        .build()
    }

    override fun reply(chanDescriptor: ChanDescriptor): HttpUrl {
      return sys.newBuilder()
        .addPathSegment(chanDescriptor.boardCode())
        .addPathSegment("post")
        .build()
    }

    override fun delete(post: ChanPost): HttpUrl {
      val boardCode = post.boardDescriptor.boardCode

      return sys.newBuilder()
        .addPathSegment(boardCode)
        .addPathSegment("imgboard.php")
        .build()
    }

    override fun report(post: ChanPost): HttpUrl {
      val boardCode = post.boardDescriptor.boardCode

      return sys.newBuilder()
        .addPathSegment(boardCode)
        .addPathSegment("imgboard.php")
        .addQueryParameter("mode", "report")
        .addQueryParameter("no", post.postNo().toString())
        .build()
    }

    override fun login(): HttpUrl {
      return sys.newBuilder().addPathSegment("auth").build()
    }

    override fun search(): HttpUrl {
      return search
    }

    override fun archive(boardDescriptor: BoardDescriptor): HttpUrl {
      return b.newBuilder()
        .addPathSegment(boardDescriptor.boardCode)
        .addPathSegment("archive")
        .build()
    }
  }

  @OptIn(InternalCoroutinesApi::class)
  private val actions: SiteActions = object : SiteActions {

    override suspend fun boards(): JsonReaderRequest.JsonReaderResponse<SiteBoards> {
      val request = Request.Builder()
        .url(endpoints().boards().toString())
        .get()
        .build()

      return Chan4BoardsRequest(
        siteDescriptor(),
        boardManager,
        request,
        proxiedOkHttpClient
      ).execute()
    }

    override suspend fun pages(board: ChanBoard): JsonReaderRequest.JsonReaderResponse<BoardPages> {
      val request = Request.Builder()
        .url(endpoints().pages(board))
        .get()
        .build()

      return Chan4PagesRequest(
        board.boardDescriptor,
        board.pages,
        request,
        proxiedOkHttpClient
      ).execute()
    }

    override suspend fun post(replyChanDescriptor: ChanDescriptor, replyMode: ReplyMode): Flow<SiteActions.PostResult> {
      val replyCall = Chan4ReplyCall(
        site = this@Chan4,
        replyChanDescriptor = replyChanDescriptor,
        replyMode = replyMode,
        replyManager = replyManager,
        boardManager = boardManager,
        appConstants = appConstants,
        staticBoardFlagInfoRepository = staticBoardFlagInfoRepository
      )

      return httpCallManager.makePostHttpCallWithProgress(replyCall)
        .map { replyCallResult ->
          when (replyCallResult) {
            is HttpCall.HttpCallWithProgressResult.Success -> {
              return@map SiteActions.PostResult.PostComplete(
                replyCallResult.httpCall.replyResponse
              )
            }
            is HttpCall.HttpCallWithProgressResult.Progress -> {
              return@map SiteActions.PostResult.UploadingProgress(
                replyCallResult.fileIndex,
                replyCallResult.totalFiles,
                replyCallResult.percent
              )
            }
            is HttpCall.HttpCallWithProgressResult.Fail -> {
              return@map SiteActions.PostResult.PostError(
                replyCallResult.error
              )
            }
          }
        }
    }

    override suspend fun delete(deleteRequest: DeleteRequest): SiteActions.DeleteResult {
      val deleteResult = httpCallManager.makeHttpCall(
        Chan4DeleteHttpCall(this@Chan4, deleteRequest)
      )

      return when (deleteResult) {
          is HttpCall.HttpCallResult.Success -> {
            SiteActions.DeleteResult.DeleteComplete(
              deleteResult.httpCall.deleteResponse
            )
          }
          is HttpCall.HttpCallResult.Fail -> {
            SiteActions.DeleteResult.DeleteError(
              deleteResult.error
            )
          }
      }
    }

    @Suppress("MoveVariableDeclarationIntoWhen")
    override suspend fun <T : AbstractLoginRequest> login(loginRequest: T): SiteActions.LoginResult {
      val chan4LoginRequest = loginRequest as Chan4LoginRequest

      passUser.set(chan4LoginRequest.user)
      passPass.set(chan4LoginRequest.pass)

      val loginResult = httpCallManager.makeHttpCall(
        Chan4PassHttpCall(this@Chan4, chan4LoginRequest)
      )

      when (loginResult) {
        is HttpCall.HttpCallResult.Success -> {
          val loginResponse = requireNotNull(loginResult.httpCall.loginResponse) { "loginResponse is null" }

          return when (loginResponse) {
            is Chan4LoginResponse.Success -> {
              passToken.set(loginResponse.authCookie)
              SiteActions.LoginResult.LoginComplete(loginResponse)
            }
            is Chan4LoginResponse.Failure -> {
              SiteActions.LoginResult.LoginError(loginResponse.errorMessage)
            }
          }
        }
        is HttpCall.HttpCallResult.Fail -> {
          return SiteActions.LoginResult.LoginError(loginResult.error.errorMessageOrClassName())
        }
      }
    }

    override fun postRequiresAuthentication(): Boolean {
      return !isLoggedIn()
    }

    @Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA")
    override fun postAuthenticate(): SiteAuthentication {
      return when (captchaType.get()) {
        CaptchaType.V2JS -> SiteAuthentication.fromCaptcha2(CAPTCHA_KEY, "https://boards.4chan.org")
        CaptchaType.V2NOJS -> SiteAuthentication.fromCaptcha2nojs(CAPTCHA_KEY, "https://boards.4chan.org")
      }
    }

    override fun logout() {
      passToken.remove()
      passUser.remove()
      passPass.remove()
    }

    override fun isLoggedIn(): Boolean {
      return passToken.get().isNotEmpty()
    }

    override fun loginDetails(): Chan4LoginRequest {
      return Chan4LoginRequest(
        passUser.get(),
        passPass.get()
      )
    }

    override suspend fun <T : SearchParams> search(searchParams: T): SearchResult {
      searchParams as Chan4SearchParams
      val page = searchParams.getCurrentPage()
      val boardCode = searchParams.boardCode

      // https://find.4chan.org/?q=test&b=g&o=0
      val searchUrl = requireNotNull(endpoints().search())
        .newBuilder()
        .addQueryParameter("q", searchParams.query)
        .addBoardCodeParameter(boardCode)
        .addQueryParameter("o", page.toString())
        .build()

      val requestBuilder = Request.Builder()
        .url(searchUrl)
        .get()

      this@Chan4.requestModifier().modifySearchGetRequest(this@Chan4, requestBuilder)

      return Chan4SearchRequest(
        requestBuilder.build(),
        proxiedOkHttpClient,
        searchParams
      ).execute()
    }

    override suspend fun archive(boardDescriptor: BoardDescriptor): ModularResult<List<NativeArchivePost>> {
      val archiveUrl = requireNotNull(endpoints().archive(boardDescriptor))

      val requestBuilder = Request.Builder()
        .url(archiveUrl)
        .get()

      this@Chan4.requestModifier().modifyArchiveGetRequest(this@Chan4, requestBuilder)

      return Chan4ArchiveThreadsRequest(
        requestBuilder.build(),
        proxiedOkHttpClient,
        simpleCommentParser
      ).execute()
    }

    private fun HttpUrl.Builder.addBoardCodeParameter(boardCode: String?): HttpUrl.Builder {
      if (boardCode.isNullOrEmpty()) {
        return this
      }

      return addQueryParameter("b", boardCode)
    }
  }

  override fun enabled(): Boolean {
    return true
  }

  override fun name(): String {
    return SITE_NAME
  }

  override fun siteDescriptor(): SiteDescriptor {
    return SITE_DESCRIPTOR
  }

  override fun icon(): SiteIcon {
    return SiteIcon.fromFavicon(imageLoaderV2, "https://s.4cdn.org/image/favicon.ico".toHttpUrl())
  }

  override fun resolvable(): SiteUrlHandler {
    return URL_HANDLER
  }

  override fun siteFeature(siteFeature: Site.SiteFeature): Boolean {
    return true // everything is supported
  }

  override fun boardsType(): Site.BoardsType {
    // yes, boards.json
    return Site.BoardsType.DYNAMIC
  }

  override fun boardFeature(boardFeature: Site.BoardFeature, board: ChanBoard): Boolean {
    return when (boardFeature) {
      // yes, we support image posting.
      Site.BoardFeature.POSTING_IMAGE -> true
      // depends if the board supports it.
      Site.BoardFeature.POSTING_SPOILER -> board.spoilers
      else -> false
    }
  }

  override fun endpoints(): SiteEndpoints {
    return endpoints
  }

  override fun requestModifier(): SiteRequestModifier<Site> {
    return siteRequestModifier as SiteRequestModifier<Site>
  }

  override fun chanReader(): ChanReader {
    return FutabaChanReader(
      archivesManager,
      postFilterManager,
      mockReplyManager,
      siteManager,
      boardManager
    )
  }

  override fun actions(): SiteActions {
    return actions
  }

  override fun commentParserType(): CommentParserType {
    return CommentParserType.Default
  }

  override fun getChunkDownloaderSiteProperties(): ChunkDownloaderSiteProperties {
    return chunkDownloaderSiteProperties
  }

  override fun postingLimitationInfo(): SitePostingLimitationInfo {
    return SitePostingLimitationInfo(
      postMaxAttachables = ConstantAttachablesCount(count = 1),
      postMaxAttachablesTotalSize = PasscodeDependantMaxAttachablesTotalSize(
        siteManager = siteManager
      )
    )
  }

  override fun <T : Setting<*>> getSettingBySettingId(settingId: SiteSetting.SiteSettingId): T? {
    return when (settingId) {
      SiteSetting.SiteSettingId.LastUsedCountryFlagPerBoard -> lastUsedFlagPerBoard as T
      else -> super.getSettingBySettingId(settingId)
    }
  }

  override fun settings(): MutableList<SiteSetting> {
    val settings = ArrayList<SiteSetting>()

    settings.add(SiteOptionsSetting("Captcha type", null, captchaType, listOf("Javascript", "Noscript")))
    settings.addAll(super.settings())

    return settings
  }

  override fun siteGlobalSearchType(): SiteGlobalSearchType = SiteGlobalSearchType.SimpleQueryBoardSearch

  class Chan4SiteRequestModifier(
    site: Chan4,
    appConstants: AppConstants
  ) : SiteRequestModifier<Chan4>(site, appConstants) {

    override fun modifyHttpCall(httpCall: HttpCall, requestBuilder: Request.Builder) {
      super.modifyHttpCall(httpCall, requestBuilder)

      if (httpCall is Chan4ReplyCall && httpCall.replyMode == ReplyMode.ReplyModeUsePasscode) {
        if (site.actions().isLoggedIn()) {
          val passTokenSetting = site.passToken
          requestBuilder.addHeader("Cookie", "pass_id=" + passTokenSetting.get())
        }
      }
    }

    override fun modifyWebView(webView: WebView) {
      super.modifyWebView(webView)

      val sys = HttpUrl.Builder()
        .scheme("https")
        .host("sys.4chan.org")
        .build()

      val cookieManager = CookieManager.getInstance()
      cookieManager.removeAllCookies(null)

      if (site.actions().isLoggedIn()) {
        val passTokenSetting = site.passToken
        val passCookies = arrayOf("pass_enabled=1;", "pass_id=" + passTokenSetting.get() + ";")
        val domain = sys.scheme + "://" + sys.host + "/"

        for (cookie in passCookies) {
          cookieManager.setCookie(domain, cookie)
        }
      }
    }
  }

  @DoNotStrip
  enum class CaptchaType(val value: String) : OptionSettingItem {
    V2JS("v2js"),
    V2NOJS("v2nojs");

    override fun getKey(): String {
      return value
    }

  }

  companion object {
    const val SITE_NAME = "4chan"
    val SITE_DESCRIPTOR = SiteDescriptor.create(SITE_NAME)

    @JvmStatic
    val URL_HANDLER: SiteUrlHandler = object : SiteUrlHandler {
      private val mediaHosts = arrayOf(
        "https://i.4cdn.org/".toHttpUrl(),
        "https://is2.4chan.org/".toHttpUrl(),
      )

      override fun getSiteClass(): Class<out Site> {
        return Chan4::class.java
      }

      override fun matchesMediaHost(url: HttpUrl): Boolean {
        return containsMediaHostUrl(url, mediaHosts)
      }

      override fun matchesName(value: String): Boolean {
        return value == SITE_NAME
      }

      override fun respondsTo(url: HttpUrl): Boolean {
        val host = url.host

        return host == "4chan.org"
          || host == "www.4chan.org"
          || host == "boards.4chan.org"
          || host == "4channel.org"
          || host == "www.4channel.org"
          || host == "boards.4channel.org"
          || host == "a.4cdn.org"
          || host == "i.4cdn.org"
          || host == "s.4cdn.org"
          || host == "sys.4chan.org"
          || host == "boards.4chan.org"
          || host == "find.4chan.org"
      }

      override fun desktopUrl(chanDescriptor: ChanDescriptor, postNo: Long?): String {
        if (chanDescriptor.isCatalogDescriptor()) {
          return if (postNo != null && postNo > 0) {
            "https://boards.4chan.org/" + chanDescriptor.boardCode() + "/thread/" + postNo
          } else {
            "https://boards.4chan.org/" + chanDescriptor.boardCode() + "/"
          }
        }

        if (chanDescriptor.isThreadDescriptor()) {
          val threadNo = (chanDescriptor as ChanDescriptor.ThreadDescriptor).threadNo

          var url = "https://boards.4chan.org/" + chanDescriptor.boardCode() + "/thread/" + threadNo
          if (postNo != null && postNo > 0 && threadNo != postNo) {
            url += "#p$postNo"
          }

          return url
        }

        return "https://boards.4chan.org/" + chanDescriptor.boardCode() + "/"
      }

      override fun resolveChanDescriptor(site: Site, url: HttpUrl): ResolvedChanDescriptor? {
        val parts = url.pathSegments
        if (parts.isEmpty()) {
          return null
        }

        val boardCode = parts[0]
        if (site.board(boardCode) == null) {
          return null
        }

        if (parts.size < 3) {
          // Board mode
          return ResolvedChanDescriptor(ChanDescriptor.CatalogDescriptor.create(site.name(), boardCode))
        }

        // Thread mode
        val threadNo = (parts[2].toIntOrNull() ?: -1).toLong()
        var postId = -1L
        val fragment = url.fragment

        if (fragment != null) {
          val index = fragment.indexOf("p")
          if (index >= 0) {
            postId = (fragment.substring(index + 1).toIntOrNull() ?: -1).toLong()
          }
        }

        if (threadNo < 0L) {
          return null
        }

        val markedPostNo = if (postId >= 0L) {
          postId
        } else {
          null
        }

        val threadDescriptor = ChanDescriptor.ThreadDescriptor.create(
          site.name(),
          boardCode,
          threadNo
        )

        return ResolvedChanDescriptor(threadDescriptor, markedPostNo)
      }
    }

  }

}