package com.github.adamantcheese.chan.features.settings

import android.content.Context
import com.airbnb.epoxy.EpoxyController
import com.airbnb.epoxy.EpoxyRecyclerView
import com.github.adamantcheese.chan.Chan
import com.github.adamantcheese.chan.R
import com.github.adamantcheese.chan.StartActivity
import com.github.adamantcheese.chan.controller.Controller
import com.github.adamantcheese.chan.core.cache.CacheHandler
import com.github.adamantcheese.chan.core.cache.FileCacheV2
import com.github.adamantcheese.chan.core.database.DatabaseManager
import com.github.adamantcheese.chan.core.manager.FilterWatchManager
import com.github.adamantcheese.chan.core.manager.WakeManager
import com.github.adamantcheese.chan.core.settings.ChanSettings
import com.github.adamantcheese.chan.ui.controller.LogsController
import com.github.adamantcheese.chan.ui.controller.ToolbarNavigationController
import com.github.adamantcheese.chan.ui.controller.ToolbarNavigationController.ToolbarSearchCallback
import com.github.adamantcheese.chan.ui.epoxy.epoxyDividerView
import com.github.adamantcheese.chan.utils.AndroidUtils
import com.github.adamantcheese.chan.utils.AndroidUtils.inflate
import com.github.adamantcheese.chan.utils.exhaustive
import com.github.adamantcheese.model.repository.ChanPostRepository
import com.github.adamantcheese.model.repository.InlinedFileInfoRepository
import com.github.adamantcheese.model.repository.MediaServiceLinkExtraContentRepository
import com.github.adamantcheese.model.repository.SeenPostRepository
import io.reactivex.processors.BehaviorProcessor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import javax.inject.Inject

class DeveloperSettingsControllerV2(context: Context) : Controller(context), ToolbarSearchCallback {

  @Inject
  lateinit var databaseManager: DatabaseManager

  @Inject
  lateinit var fileCacheV2: FileCacheV2

  @Inject
  lateinit var cacheHandler: CacheHandler

  @Inject
  lateinit var seenPostRepository: SeenPostRepository

  @Inject
  lateinit var chanPostRepository: ChanPostRepository

  @Inject
  lateinit var mediaServiceLinkExtraContentRepository: MediaServiceLinkExtraContentRepository

  @Inject
  lateinit var inlinedFileInfoRepository: InlinedFileInfoRepository

  @Inject
  lateinit var filterWatchManager: FilterWatchManager

  @Inject
  lateinit var wakeManager: WakeManager

  lateinit var recyclerView: EpoxyRecyclerView

  private val normalSettingsGraph by lazy { buildSettingsGraph() }
  private val searchSettingsGraph by lazy { buildSettingsGraph().apply { rebuildScreens() } }

  private val onSearchEnteredSubject = BehaviorProcessor.create<String>()

  @OptIn(FlowPreview::class)
  override fun onCreate() {
    super.onCreate()
    Chan.inject(this)

    view = inflate(context, R.layout.controller_developer_settings)
    recyclerView = view.findViewById(R.id.archives_recycler_view)

    navigation.buildMenu()
      .withItem(R.drawable.ic_search_white_24dp) {
        (navigationController as ToolbarNavigationController).showSearch()
      }
      .build()

    mainScope.launch {
      onSearchEnteredSubject
        .asFlow()
        .debounce(DEBOUNCE_TIME_MS)
        .collect { query ->
          if (query.length < MIN_QUERY_LENGTH) {
            rebuildDefaultScreen()
            return@collect
          }

          rebuildScreenWithSearchQuery(query)
        }
    }

    rebuildDefaultScreen()
  }

  override fun onSearchVisibilityChanged(visible: Boolean) {
    if (!visible) {
      rebuildDefaultScreen()
    }
  }

  override fun onSearchEntered(entered: String?) {
    onSearchEnteredSubject.onNext(entered ?: "")
  }

  private fun rebuildDefaultScreen() {
    rebuildScreen(SettingsIdentifier.Screen.DeveloperSettingsScreen)
  }

  private fun rebuildScreenWithSearchQuery(query: String) {
    val graph = searchSettingsGraph

    recyclerView.withModels {
      renderSearchScreen(graph, query)
    }
  }

  private fun rebuildScreen(screen: SettingsIdentifier.Screen) {
    val settingsScreen = normalSettingsGraph[screen]

    recyclerView.withModels {
      navigation.title = settingsScreen.title

      renderScreen(settingsScreen)
    }
  }

  private fun rebuildSetting(
    screen: SettingsIdentifier.Screen,
    group: SettingsIdentifier.Group,
    setting: SettingsIdentifier
  ) {
    val settingsScreen = normalSettingsGraph[screen].apply { rebuildSetting(group, setting) }

    recyclerView.withModels {
      navigation.title = settingsScreen.title

      renderScreen(settingsScreen)
    }
  }

  private fun EpoxyController.renderSearchScreen(graph: SettingsGraph, query: String) {
    graph.iterateScreens { settingsScreen ->
      settingsScreen.iterateGroupsIndexed { _, settingsGroup ->
        settingsGroup.iterateSettingsIndexedByQuery(query) { settingIndex, setting ->
          epoxySettingLink {
            id("epoxy_setting_link_${setting.settingsIdentifier.identifier}")
            topDescription(setting.topDescription)
            bottomDescription(setting.bottomDescription)

            clickListener {
              when (val clickAction = setting.callback.invoke()) {
                SettingClickAction.RefreshCurrentScreen -> {
                  rebuildSetting(
                    settingsScreen.screenIdentifier,
                    settingsGroup.groupIdentifier,
                    setting.settingsIdentifier
                  )
                }
                is SettingClickAction.OpenScreen -> rebuildScreen(clickAction.screenIdentifier)
              }.exhaustive
            }
          }

          if (settingIndex != settingsGroup.lastIndex()) {
            epoxyDividerView {
              id("epoxy_divider_${settingIndex}")
            }
          }
        }
      }
    }
  }

  private fun EpoxyController.renderScreen(settingsScreen: SettingsScreen) {
    settingsScreen.iterateGroupsIndexed { _, group ->
      epoxySettingsGroupTitle {
        id("epoxy_settings_group_title_${group.groupIdentifier.identifier}")
        groupTitle(group.groupTitle)
      }

      group.iterateGroupsIndexed { settingIndex, setting ->
        epoxySettingLink {
          id("epoxy_setting_link_${setting.settingsIdentifier.identifier}")
          topDescription(setting.topDescription)
          bottomDescription(setting.bottomDescription)

          clickListener {
            when (val clickAction = setting.callback.invoke()) {
              SettingClickAction.RefreshCurrentScreen -> {
                rebuildSetting(
                  settingsScreen.screenIdentifier,
                  group.groupIdentifier,
                  setting.settingsIdentifier
                )
              }
              is SettingClickAction.OpenScreen -> rebuildScreen(clickAction.screenIdentifier)
            }.exhaustive
          }
        }

        if (settingIndex != group.lastIndex()) {
          epoxyDividerView {
            id("epoxy_divider_${settingIndex}")
          }
        }
      }
    }
  }

  private fun buildSettingsGraph(): SettingsGraph {
    val graph = SettingsGraph()

    graph += buildSettingScreen()

    return graph
  }

  private fun buildSettingScreen(): SettingsScreen.SettingsScreenBuilder {
    val identifier = SettingsIdentifier.Screen.DeveloperSettingsScreen

    return SettingsScreen.SettingsScreenBuilder(
      screenIdentifier = identifier,
      buildFunction = fun(): SettingsScreen {
        val screen = SettingsScreen(
          title = context.getString(R.string.settings_developer),
          screenIdentifier = SettingsIdentifier.Screen.DeveloperSettingsScreen
        )

        screen += buildSettingsGroup()

        return screen
      }
    )
  }

  private fun buildSettingsGroup(): SettingsGroup.SettingsGroupBuilder {
    val identifier = SettingsIdentifier.Group.DeveloperSettingsGroup

    return SettingsGroup.SettingsGroupBuilder(
      groupIdentifier = identifier,
      buildFunction = fun(): SettingsGroup {
        val group = SettingsGroup(
          groupIdentifier = identifier
        )

        group += SettingV2.createBuilder(
          context = context,
          identifier = SettingsIdentifier.Developer.ViewLogs,
          topDescriptionIdFunc = {
            R.string.settings_open_logs
          },
          callback = {
            navigationController!!.pushController(LogsController(context))
          }
        )

        group += SettingV2.createBuilder(
          context = context,
          identifier = SettingsIdentifier.Developer.EnableDisableVerboseLogs,
          topDescriptionIdFunc = {
            if (ChanSettings.verboseLogs.get()) {
              R.string.settings_disable_verbose_logs
            } else {
              R.string.settings_enable_verbose_logs
            }
          },
          callback = {
            ChanSettings.verboseLogs.setSync(!ChanSettings.verboseLogs.get())
            (context as StartActivity).restartApp()
          }
        )

        group += SettingV2.createBuilder(
          context = context,
          identifier = SettingsIdentifier.Developer.CrashApp,
          topDescriptionIdFunc = {
            R.string.settings_crash_app
          },
          callback = {
            throw RuntimeException("Debug crash")
          }
        )

        group += SettingV2.createBuilder(
          context = context,
          identifier = SettingsIdentifier.Developer.ClearFileCache,
          topDescriptionStringFunc = {
            context.getString(R.string.settings_clear_file_cache)
          },
          bottomDescriptionStringFunc = {
            val cacheSize = cacheHandler.getSize() / 1024 / 1024
            context.getString(R.string.settings_clear_file_cache_bottom_description, cacheSize)
          },
          callback = {
            fileCacheV2.clearCache()
            AndroidUtils.showToast(context, "Cleared image cache")
          }
        )

        group += SettingV2.createBuilder(
          context = context,
          identifier = SettingsIdentifier.Developer.ShowDatabaseSummary,
          topDescriptionIdFunc = {
            R.string.settings_database_summary
          },
          callback = {
            showDatabaseSummary()
          }
        )

        group += SettingV2.createBuilder(
          context = context,
          identifier = SettingsIdentifier.Developer.FilterWatchIgnoreReset,
          topDescriptionIdFunc = {
            R.string.settings_clear_ignored_filter_watches
          },
          callback = {
            filterWatchManager.clearFilterWatchIgnores()
            AndroidUtils.showToast(context, "Cleared ignores")
          }
        )

        return group
      }
    )
  }

  // TODO(archives):
  private fun showDatabaseSummary() {
//        //DATABASE SUMMARY
//        TextView summaryText = new TextView(context);
//        summaryText.setText("Database summary:\n" + databaseManager.getSummary());
//        summaryText.setPadding(dp(15), dp(5), 0, 0);
//        wrapper.addView(summaryText);

//        //DATABASE RESET
//        Button resetDbButton = new Button(context);
//        resetDbButton.setOnClickListener(v -> {
//            databaseManager.reset();
//            ((StartActivity) context).restartApp();
//        });
//        resetDbButton.setText("Delete database & restart");
//        wrapper.addView(resetDbButton);
//
//        // Clear seen posts table
//        addClearSeenPostsButton(wrapper);
//
//        // Clear posts table
//        addClearPostsButton(wrapper);
//
//        // Clear external link extra info table
//        addClearExternalLinkExtraInfoTable(wrapper);
//
//        // Clear inlined files info table
//        addClearInlinedFilesInfoTable(wrapper);
  }

  companion object {
    private const val TAG = "DeveloperSettingsControllerV2"
    private const val MIN_QUERY_LENGTH = 4
    private const val DEBOUNCE_TIME_MS = 250L
  }
}