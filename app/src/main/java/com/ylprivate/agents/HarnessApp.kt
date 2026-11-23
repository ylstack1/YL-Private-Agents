package com.ylprivate.agents

import android.app.Application
import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.room.Room
import com.ylprivate.agents.agent.AgentEngine
import com.ylprivate.agents.agent.TodoStore
import com.ylprivate.agents.data.ChatBackupManager
import com.ylprivate.agents.data.CheckpointStore
import com.ylprivate.agents.data.ImageStore
import com.ylprivate.agents.data.KeyStoreManager
import com.ylprivate.agents.data.ProviderRepository
import com.ylprivate.agents.data.SessionRepository
import com.ylprivate.agents.data.SettingsRepository
import com.ylprivate.agents.data.db.AppDatabase
import com.ylprivate.agents.llm.ProviderFactory
import com.ylprivate.agents.llm.effectiveApiKey
import com.ylprivate.agents.tools.ToolRegistry
import com.ylprivate.agents.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HarnessApp : Application() {
    private val memoryScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        com.ylprivate.agents.local.GpuDeliveryFactory.attach(this)
    }
    lateinit var container: AppContainer
        private set

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate() {
        super.onCreate()
        // Paused LazyColumn prefetch crashes in LayoutNode.onChildRemoved when chat items change.
        ComposeFoundationFlags.isPausableCompositionInPrefetchEnabled = false
        WebView.enableSlowWholeDocumentDraw()
        // Before any workspace is resolved, so no tool ever sees a filtered shared-storage tree.
        com.ylprivate.agents.data.env.StorageGate.install(packageName,
            granted = { com.ylprivate.agents.data.env.StorageAccess.isGranted(this) })
        container = AppContainer(this)
        com.ylprivate.agents.data.env.StorageGate.installPrivilegedRoute { cwd ->
            container.deviceAccess.liveTier(cwd).let {
                it == com.ylprivate.agents.data.env.ExecutionTier.ROOT || it == com.ylprivate.agents.data.env.ExecutionTier.PRIVILEGED
            }
        }
        memoryScope.launch { container.settings.ensureRumbleCombo() }
        // A force stop clears every alarm without telling the app, so set them again on each start.
        memoryScope.launch { runCatching { container.reminders.rescheduleAll() } }
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            memoryScope.launch { container.localModels.releaseCachedModel() }
        }
    }
    override fun onLowMemory() {
        super.onLowMemory()
        if (::container.isInitialized) memoryScope.launch { container.localModels.releaseCachedModel() }
    }
}

class AppContainer(val appContext: Context) {
    /** Deep-link channel: run-result notifications deliver a session id here. */
    val pendingSessionId = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pendingSubagentChat = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)
    /** Successful repository imports open their own files, independent of the old chat's workspace. */
    val pendingImportedWorkspace = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Share-target channel: content awaiting a regular Chat destination and draft import. */
    val pendingShare = kotlinx.coroutines.flow.MutableStateFlow<com.ylprivate.agents.data.PendingShare?>(null)

    /** Selected-code prompt waiting for its specific chat composer. */
    val pendingCodeDraft = kotlinx.coroutines.flow.MutableStateFlow<com.ylprivate.agents.ui.files.CodeChatDraft?>(null)

    /** Build/test failure prompt waiting for a fresh chat to run it. */
    val pendingAgentPrompt = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** In-app nav channel: a screen asking Settings to scroll to a section. */
    val pendingSettingsScroll = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val keys = KeyStoreManager(appContext)
    init { com.ylprivate.agents.llm.ProviderNetwork.settings = keys::providerProxy }
    val settings = SettingsRepository(appContext)

    /** Durable request log; opened lazily so a user who never enables it pays nothing. */
    val debugLog: com.ylprivate.agents.llm.debug.DebugLogRepository by lazy {
        com.ylprivate.agents.llm.debug.DebugLogRepository(
            com.ylprivate.agents.llm.debug.DebugLogDatabase.open(appContext).dao(),
        ) { id -> db.dao().session(id)?.title }
    }

    @Volatile private var debugPersist = false

    init {
        // Provider debug console: persisted switches. History lives in its own database,
        // so nothing has to be restored into memory at startup.
        val debugCapture = com.ylprivate.agents.llm.debug.DebugCapture
        // The file-based archive is gone; drop its leftover so no old log lingers.
        runCatching { java.io.File(appContext.filesDir, "provider-debug-traces.enc").delete() }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            settings.settings.collect { value ->
                debugCapture.enabled = value.debugConsoleEnabled
                debugCapture.captureBodies = value.debugConsoleBodies
                debugPersist = value.debugConsolePersist && value.debugConsoleEnabled
            }
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            // A live stream republishes every half second; debounce the write so a
            // running answer does not hit SQLite on every event.
            var pending: kotlinx.coroutines.Job? = null
            debugCapture.store.traces.collect { traces ->
                if (!debugPersist) return@collect
                pending?.cancel()
                pending = launch {
                    kotlinx.coroutines.delay(800)
                    debugLog.save(traces)
                    debugLog.prune()
                }
            }
        }
    }
    private val configurationRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val pluginManager = com.ylprivate.agents.plugins.PluginManager(appContext, configurationRevision,
        com.ylprivate.agents.plugins.PluginDownloads(githubToken = { github.accessToken() })) { pluginRuntime }
    private val pluginRuntime by lazy {
        object : com.ylprivate.agents.plugins.PluginRuntime {
            override val ready get() = linuxEnv.isReady
            override suspend fun available(wanted: List<String>): List<String> {
                val index = linuxEnv.getPackageIndex()
                // A fresh environment needs its core shell before any runtime can start.
                val core = if (linuxEnv.bashExecutable() == null) linuxEnv.corePackages else emptyList()
                return (core + wanted).distinct().filter { it in index }
            }
            override suspend fun install(packages: List<String>, requester: String,
                progress: suspend (com.ylprivate.agents.data.env.PackageInstallStatus) -> Unit) {
                if (packages.isNotEmpty()) linuxEnv.install(packages, requester, progress).getOrThrow()
            }
            override fun process(command: String) = linuxEnv.shellProcessBuilder(command)
        }
    }
    val configuration = com.ylprivate.agents.config.HarnessConfigRepository(appContext, keys, pluginManager, configurationRevision)
    val pluginHooks = com.ylprivate.agents.config.PluginHooks(appContext) { linuxEnv.shellProcessBuilder(it) }
    init {
        // Plugins with automatic updates are checked shortly after start, away from the first screen's work.
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            kotlinx.coroutines.delay(30_000)
            runCatching { pluginManager.autoUpdate() }
        }
    }
    internal val runtimePolicy = AppRuntimePolicy(appContext, settings)
    /** Voice providers (speech-to-text and text-to-speech) with per-provider keys. */
    val voiceProviders = com.ylprivate.agents.data.audio.VoiceProviderRepository(appContext, keys)
    /** Microsoft Edge voice catalogue, cached on disk for a day. */
    val edgeVoices = com.ylprivate.agents.data.audio.EdgeVoiceCatalog(appContext)
    /** Keeps the advertised Edge build current so read-aloud is not refused. */
    val edgeVersion = com.ylprivate.agents.data.audio.EdgeVersionProvider(appContext)
    /** Reads assistant replies with Android TTS or the selected voice provider. */
    val tts = com.ylprivate.agents.data.audio.TtsController(appContext, settings, keys, voiceProviders, edgeVoices, edgeVersion)
    internal val editorScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val editorDrafts = com.ylprivate.agents.ui.files.EditorDraftStore(
        java.io.File(appContext.noBackupFilesDir, "editor-drafts"), editorScope)
    internal val editorTabs = com.ylprivate.agents.ui.files.EditorTabsStore(
        java.io.File(appContext.noBackupFilesDir, "editor-tabs"), editorScope)
    val localModels = com.ylprivate.agents.local.LocalModelManager(appContext)
    val chatGpt = com.ylprivate.agents.chatgpt.ChatGptAccounts(keys::chatGptCredentials, keys::putChatGptCredentials,
        onConnected = { config ->
            settings.setActiveSelection(config.id, config.model)
        }, onDisconnected = { id ->
            runManager.runningSessionIds.value.filter {
                val saved = runManager.controls.flow(it).value
                saved.provider?.id == id || id in saved.usedProviderIds ||
                    runManager.subagents.runs.value.any { run -> run.sessionId == it && run.status.active && run.providerId == id }
            }
                .forEach { runManager.stopAndJoin(it) }
        })
    val providers: ProviderRepository = ProviderRepository(appContext, keys, localModels, chatGpt, settings)

    init {
        com.ylprivate.agents.llm.RotatingKeyProvider.pools = providers::controls
        ProviderFactory.chatGptProvider = com.ylprivate.agents.chatgpt.ChatGptProvider(chatGpt::accessToken,
            modelEntry = { id, model -> chatGpt.state.value.accounts.firstOrNull { it.providerId == id }?.models?.firstOrNull { it.id == model } })
        com.ylprivate.agents.llm.ModelCatalog.chatGptModels = { chatGpt.refreshModels(it) }
        ProviderFactory.localProvider = com.ylprivate.agents.local.LocalModelProvider(localModels) {
            settings.settings.first().localModelAgentContext
        }
    }
    val screenshotPolicy = com.ylprivate.agents.data.ScreenshotPolicy()

    private val db = Room.databaseBuilder(appContext, AppDatabase::class.java, "harness.db")
        .addMigrations(
            AppDatabase.MIGRATION_26_27, AppDatabase.MIGRATION_25_26, AppDatabase.MIGRATION_24_25, AppDatabase.MIGRATION_23_24, AppDatabase.MIGRATION_22_23, AppDatabase.MIGRATION_21_22, AppDatabase.MIGRATION_20_21, AppDatabase.MIGRATION_19_20, AppDatabase.MIGRATION_18_19, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17, AppDatabase.MIGRATION_17_18,
        )
        .addCallback(AppDatabase.OVERSIZED_ROW_SANITIZER)
        // Only kicks in when no migration path exists (pre-v4 databases);
        // the v4→v5 path above preserves sessions and usage totals.
        .fallbackToDestructiveMigration(true)
        .build()
    val images = ImageStore(appContext)
    val sessions = SessionRepository(db, images)
    val memory = com.ylprivate.agents.data.MemoryRepository(db, settings)
    val chatModels = com.ylprivate.agents.data.ChatModelRepository(settings, providers, sessions)
    suspend fun startChatAgent(profile: com.ylprivate.agents.data.ChatAgentProfile): String {
        profile.validated()
        val id = sessions.createSession(profile.name, workspace.currentProjectOnce().id,
            com.ylprivate.agents.data.ConversationConfig(kind = com.ylprivate.agents.data.ConversationKind.CHAT, agent = profile))
        chatModels.initialize(id)
        settings.setLastActiveSessionId(id)
        return id
    }

    private suspend fun canRecallChat(from: String?, target: String): Boolean {
        val source = from?.let { sessions.session(it) } ?: return false
        val destination = sessions.session(target) ?: return false
        val config = com.ylprivate.agents.data.ConversationConfig.decode(source.conversationJson)
        return com.ylprivate.agents.data.ChatHistoryPolicy.canRead(config,
            com.ylprivate.agents.data.ConversationConfig.decode(destination.conversationJson), settings.settings.first())
    }
    val chatBackup by lazy { ChatBackupManager(db, appContext, workspace) }
    val snippets = com.ylprivate.agents.data.SnippetRepository(db.dao())

    val sshConnections = com.ylprivate.agents.workspace.SshConnections(keys)
    val workspace = WorkspaceManager(appContext, db.dao(), sshConnections)
    val fileHistory = com.ylprivate.agents.data.WorkspaceFileHistory(java.io.File(appContext.filesDir, "workspace-file-history"))
    val checkpoints = CheckpointStore(db.dao(), fileHistory)

    val todoStore = TodoStore()
    val repoMap = com.ylprivate.agents.repomap.RepoMapCache()
    private val fetchClient = OkHttpClient()
    val linuxEnv =
        com.ylprivate.agents.data.env.LinuxEnvironmentManager(appContext) { keys.githubToken() }
    val wirelessAdb = com.ylprivate.agents.data.env.adb.WirelessAdbManager(appContext)
    val shizuku = com.ylprivate.agents.data.env.ShizukuManager(appContext, wireless = wirelessAdb)
    val dhizuku = com.ylprivate.agents.data.env.DhizukuManager(appContext)
    val deviceAccess = com.ylprivate.agents.data.env.DeviceAccessManager(appContext, shizuku, linuxEnv,
        projectForSession = { sessions.session(it)?.projectId }, dhizuku = dhizuku)
    val shellRouter = com.ylprivate.agents.data.env.ShellTierRouter(appContext, shizuku, linuxEnv, deviceAccess)
    val codeGraph = com.ylprivate.agents.data.env.CodeGraphManager(linuxEnv, shellRouter)
    private val deployScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Package-set changes must invalidate the deployed-copy cache, or the
        // privileged tier keeps serving the old toolchain until a restart.
        // The new package set is also re-staged and deployed right away, in the
        // background, so the work overlaps the model's next turn instead of
        // stalling the first privileged shell command after an install.
        linuxEnv.deployStateListener = {
            shizuku.invalidateDeployState()
            if (shizuku.state.value == com.ylprivate.agents.data.env.ShizukuState.GRANTED &&
                shizuku.serviceState.value.isReady
            ) {
                deployScope.launch {
                    runCatching { linuxEnv.ensureShellDeploy(shizuku) }
                        .onFailure { android.util.Log.e("HarnessApp", "background shell-tier deploy failed", it) }
                }
            }
        }
    }

    val updates = com.ylprivate.agents.data.update.UpdateManager(appContext, deviceAccess)
    internal val sync by lazy { com.ylprivate.agents.data.transfer.SyncManager(keys, sshConnections) { fs, action ->
        runManager.withIdleWorkspace(fs, "Stop running tasks in this workspace before syncing files.", action)
    } }
    internal val ftpShare by lazy { com.ylprivate.agents.data.transfer.FtpShareManager(appContext) { fs, action ->
        runManager.withIdleWorkspace(fs, "Stop running tasks in this workspace before changing shared files.", action)
    } }
    val backgroundProcesses = com.ylprivate.agents.data.BgProcessStore(
        appContext, linuxEnv, shizuku, workspaceRoot = workspace.appPrivateRoot, deviceAccess = deviceAccess,
    )

    init { deviceAccess.stopBackgroundTasks = backgroundProcesses::stopPrivilegedTasks }

    @Volatile
    private var projectSkillsDir: java.io.File? = null
    private val disabledSkills = AtomicReference<Set<String>>(emptySet())
    private val webSearchProvider = AtomicReference("keyless")
    private val disabledSearchProviders = AtomicReference<Set<String>>(emptySet())
    private val searchKeyMigrated = AtomicBoolean(false)

    /** The currently visible chat's ViewModel, so WebAccessBridge can drive it from a browser. */
    @Volatile var activeChatViewModel: com.ylprivate.agents.ui.chat.ChatViewModel? = null

    /** Web Access server, created in MainActivity and stopped with the task. */
    @Volatile internal var webAccessManager: com.ylprivate.agents.webaccess.WebAccessManager? = null

    /**
     * Every search provider web_search may use, resolved per call so Settings changes
     * apply to the very next search without a restart. Tool descriptions read this
     * without rotating keys so listing the tools does not skip a key's turn.
     */
    val searchSetup: com.ylprivate.agents.tools.SearchSetup
        get() = buildSearchSetup(disabledSearchProviders.get(), rotate = false)

    /** The provider web_fetch borrows page extraction from: Tavily when it is on, else the first ready one. */
    val searchApiConfig: com.ylprivate.agents.tools.SearchApiConfig?
        get() = searchSetup.let { it.config("tavily") ?: it.apis.firstOrNull() }

    private fun buildSearchSetup(disabled: Set<String>, rotate: Boolean): com.ylprivate.agents.tools.SearchSetup {
        val apis = com.ylprivate.agents.tools.SearchProviders.apis.filter { it.id !in disabled }.mapNotNull { info ->
            searchApiConfigFor(info.id, rotate).takeIf { it.keys.isNotEmpty() || info.sharedKey != null }
        }
        return com.ylprivate.agents.tools.SearchSetup(apis, keyless = com.ylprivate.agents.tools.SearchProviders.KEYLESS !in disabled)
    }

    /**
     * Enabled keys in this call's order: rotation moves the start key, the rest are
     * failover. Tool descriptions read it without [rotate] so they do not skip a turn.
     */
    private fun searchApiConfigFor(provider: String, rotate: Boolean = true): com.ylprivate.agents.tools.SearchApiConfig {
        val pool = runCatching { keys.searchKeyPool(provider) }.getOrNull()
            ?: return com.ylprivate.agents.tools.SearchApiConfig(provider, keys.searchApiKey(provider).orEmpty())
        val ordered = (if (rotate) com.ylprivate.agents.llm.RotatingKeyProvider.orderedKeys("search:$provider", pool)
            else pool.keys.filter { it.enabled }).map { it.value }
        return com.ylprivate.agents.tools.SearchApiConfig(provider, ordered.firstOrNull().orEmpty(), ordered.drop(1))
    }

    private suspend fun resolveSearchSetup(): com.ylprivate.agents.tools.SearchSetup {
        val current = settings.settings.first()
        keys.migrateLegacySearchKey(current.webSearchProvider)
        webSearchProvider.set(current.webSearchProvider)
        disabledSearchProviders.set(current.disabledSearchProviders)
        return buildSearchSetup(current.disabledSearchProviders, rotate = true)
    }

    private suspend fun resolveSearchApiConfig(): com.ylprivate.agents.tools.SearchApiConfig? =
        resolveSearchSetup().let { it.config("tavily") ?: it.apis.firstOrNull() }

    private val cavemanSettings = AtomicReference(com.ylprivate.agents.data.AppSettings())
    val skills = com.ylprivate.agents.skills.SkillStore(
        bundled = com.ylprivate.agents.skills.SkillAssets.load(appContext.assets),
        userDir = java.io.File(appContext.filesDir, "skills").apply { mkdirs() },
        projectDir = { projectSkillsDir },
        disabled = { disabledSkills.get() },
        optionalBundled = {
            if (cavemanSettings.get().cavemanInstalled) com.ylprivate.agents.caveman.CavemanPolicy.skills else emptyMap()
        },
    )
    val browser = com.ylprivate.agents.browser.BrowserController(appContext, images)
    val registry = ToolRegistry.default(
        fetchClient, todoStore, backgroundProcesses, linuxEnv, shizuku, shellRouter, skills, codeGraph,
        imageStore = images,
        browserController = browser,
        searchApi = { searchApiConfig },
        searchApiRefresh = ::resolveSearchApiConfig,
        searchSetup = { searchSetup },
        searchSetupRefresh = ::resolveSearchSetup,
        artifactLoader = sessions::artifacts,
        toolOutputLoader = sessions::toolOutput,
        fileHistory = fileHistory,
        memory = memory,
        dhizuku = dhizuku,
        deviceAccess = deviceAccess,
        // Declared below; resolved on each call, after construction.
        pullRequests = { githubRepositories },
        sessionMessages = { sessions.messages(it) },
        appSettings = settings,
        plugins = pluginManager,
        // Declared below; resolved on each call, after construction.
        automations = com.ylprivate.agents.tools.AutomationToolHost(
            target = { sid ->
                val session = sessions.session(sid)
                val project = session?.projectId?.let { db.dao().project(it) }
                if (project == null) null else {
                    val provider = runManager.controls.flow(sid).value.provider
                    com.ylprivate.agents.tools.AutomationTarget(project.id, project.name, provider?.id, provider?.model)
                }
            },
            manager = { automation },
        ),
    )
    val mcp = com.ylprivate.agents.tools.mcp.McpManager(appContext, linuxEnv, keys, codeGraph, configuration)
    val github = com.ylprivate.agents.github.GitHubAuth(BuildConfig.GITHUB_CLIENT_ID,
        keys::githubCredential, keys::putGitHubCredential, { refreshGitHubAuth() })
    fun routedProvider(): com.ylprivate.agents.llm.LlmProvider =
        com.ylprivate.agents.llm.FallbackProvider(
                factory = ProviderFactory::create,
                resolve = { target ->
                    providers.providers.first().firstOrNull { it.id == target.providerId && it.enabled }
                        ?.takeIf { provider -> com.ylprivate.agents.llm.ProviderModelSearch.models(provider, providers.catalog(target.providerId)).any { it.id == target.model } }
                        ?.copy(model = target.model)
                },
                keyFor = { config ->
                    if (config.id == com.ylprivate.agents.llm.HarnessProvider.ID) providers.harnessApiKey()
                    else {
                        val live = providers.providers.first().firstOrNull { it.id == config.id }
                        if (live == null || live.baseUrl != config.baseUrl || live.type != config.type) ""
                        else config.effectiveApiKey(providers.apiKey(config.id))
                    }
                },
                destination = { config ->
                    if (config.id == com.ylprivate.agents.llm.HarnessProvider.ID)
                        com.ylprivate.agents.llm.HarnessProvider.pool[config.model]?.baseUrl ?: config.baseUrl
                    else config.baseUrl
                },
                currentStallTimeoutMs = {
                    com.ylprivate.agents.agent.StreamRetrier.stallTimeoutMs(settings.settings.first().streamStallTimeoutSeconds)
                },
        )

    val engine: AgentEngine = AgentEngine(
        providerFactory = { routedProvider() },
        registry = registry,
        checkpointer = checkpoints,
        imageStore = images,
        linuxEnv = linuxEnv,
        shizuku = shizuku,
        skills = skills,
        resolveConfiguration = { configuration.load(it) },
        pluginHooks = pluginHooks,
        markBufferedToolCalls = { settings.markBufferedToolCalls(it) },
        markFileBlocksDeclined = { settings.markFileBlocksDeclined(it) },
        learnContextLimit = { key, tokens -> settings.learnContextLimit(key, tokens) },
        todoStore = todoStore,
        repoMap = repoMap,
        cavemanSettings = { settings.settings.first().also { cavemanSettings.set(it); disabledSkills.set(it.disabledSkills) } },
        configuredSubagent = {
            com.ylprivate.agents.agent.SubagentConfiguration.configured(
                settings.settings.first(), providers.providers.first(), providers::apiKey,
            )?.let { connection ->
                com.ylprivate.agents.agent.SubagentConnection(
                    providers.resolveHarnessConfig(connection.config), connection.apiKey,
                )
            }
        },
        prepareGitHub = { github.refreshIfNeeded() },
        deviceAccess = deviceAccess,
        universalMemory = memory,
        chatOnlyTools = listOf(com.ylprivate.agents.tools.ReadChatTextTool(sessions::messages),
            com.ylprivate.agents.tools.ViewChatImageTool(images),
            com.ylprivate.agents.tools.BrowserReadTool(browser),
            com.ylprivate.agents.tools.SearchChatsTool { query, fuzzy, limit, offset ->
                sessions.searchRecallMessages(query, fuzzy, limit, offset)
            }.apply { canAccess = ::canRecallChat },
            com.ylprivate.agents.tools.RecentChatsTool(sessions.sessions).apply { canAccess = ::canRecallChat },
            com.ylprivate.agents.tools.ReadChatSnippetTool(sessions::messages, sessions::session).apply { canAccess = ::canRecallChat },
            com.ylprivate.agents.tools.OfferCodeHandoffTool(),
            com.ylprivate.agents.tools.GitHubApiTool(fetchClient) { github.accessToken() },
            com.ylprivate.agents.tools.GitHubSearchTool(fetchClient) { github.accessToken() },
            com.ylprivate.agents.tools.GitHubFileTool(fetchClient) { github.accessToken() }) +
            // Resolved on each call: the manager is declared below.
            com.ylprivate.agents.tools.ReminderTools.all({ reminders }, { clockAlarms }),
        remindersEnabled = { settings.settings.first().chatRemindersEnabled },
    )
    val runManager = com.ylprivate.agents.agent.RunManager(
        context = appContext,
        engine = engine,
        sessions = sessions,
        checkpoints = checkpoints,
        workspace = workspace,
        linuxEnv = linuxEnv,
        settings = settings,
        todoStore = todoStore,
        providers = providers,
        mcp = mcp,
        repoMap = repoMap,
        chatModels = chatModels,
        canRun = runtimePolicy::canRun,
    )
    val githubRepositories = com.ylprivate.agents.github.GitHubWorkspaces(this)
    val automation = com.ylprivate.agents.automation.AutomationManager(this)
    val reminders = com.ylprivate.agents.reminders.ReminderManager(appContext)
    val clockAlarms = com.ylprivate.agents.reminders.ClockAlarms(appContext)
    val projectDeletion = com.ylprivate.agents.workspace.ProjectDeletion(this)
    internal var telegram = com.ylprivate.agents.telegram.TelegramManager(this)
    val interactiveTerminal = com.ylprivate.agents.terminal.InteractiveTerminalManager(linuxEnv, runManager)
    val terminal = com.ylprivate.agents.data.TerminalManager(appContext, linuxEnv, shizuku, runManager, shellRouter, interactiveTerminal)
    val storage = com.ylprivate.agents.data.storage.StorageManager(this)
    val projectChecks = com.ylprivate.agents.data.ProjectCheckStore(appContext, terminal)

    /**
     * Applies a GitHub token change end-to-end: re-materialize the prefix
     * copies (token file + gitconfig rewrite), then redeploy the shell-tier
     * toolchain so the new auth is live without an app restart.
     */
    val nineRouter by lazy { com.ylprivate.agents.router.NineRouterManager(appContext, linuxEnv,
        runManager::acquireBackgroundKeepalive, runManager::releaseBackgroundKeepalive, providers, settings) }

    suspend fun refreshGitHubAuth() = linuxEnv.refreshGitHub(shizuku, strict = true)

    init {
        runtimePolicy.start(onBackgroundDisabled = {
            runManager.stopIfBackgroundDisabled()
            if (!runtimePolicy.canRun()) {
                // One unavailable device backend must not disable the policy observer.
                runCatching { telegram.suspendForBackground() }
                runCatching { ftpShare.stop() }
                runCatching { terminal.stopTerminal() }
                runCatching { backgroundProcesses.killAll() }
                AgentService.stop(appContext)
            }
        }, onAvailable = { telegram.restoreIfEnabled() })
        // models.dev thinking-capability catalog: serve the cached copy
        // synchronously, then refresh in the background (daily cadence).
        com.ylprivate.agents.llm.ModelsDev.load(appContext)
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            combine(settings.settings, providers.providers) { saved, available -> saved to available.map { it.id }.toSet() }
                .collect { (saved, availableIds) ->
                    val selected = listOf(saved.activeProviderId, saved.planningProviderId, saved.executionProviderId, saved.subagentProviderId)
                    if (selected.any { it != null && (com.ylprivate.agents.local.LocalModelCatalog.isLocal(it) || com.ylprivate.agents.chatgpt.ChatGptProtocol.isProvider(it)) && it !in availableIds }) {
                        settings.clearMissingLocalProviderSelections(availableIds)
                    }
                }
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Chats that held several workspaces before projects existed each move into a project of their own.
            runCatching { workspace.adoptUnfiledChats() }
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Learned wire per Harness model (chat vs anthropic vs responses),
            // persisted by the first-request probe; mirrored into memory so
            // every request path reads it synchronously.
            providers.harnessWires.collect { com.ylprivate.agents.llm.HarnessProvider.pins = it }
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Restore routes before fetching; failed refreshes leave the cache intact.
            providers.providers.first()
            when (val result = com.ylprivate.agents.llm.ModelCatalog.listModels(
                com.ylprivate.agents.llm.HarnessProvider.config,
                com.ylprivate.agents.llm.HarnessProvider.KEYLESS,
            )) {
                is com.ylprivate.agents.llm.ModelCatalog.Result.Models ->
                    providers.saveCatalog(com.ylprivate.agents.llm.HarnessProvider.ID, result.models)
                is com.ylprivate.agents.llm.ModelCatalog.Result.Failed -> Unit
            }
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            com.ylprivate.agents.llm.ModelsDev.refresh(appContext)
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settings.settings.collect {
                disabledSkills.set(it.disabledSkills)
                cavemanSettings.set(it)
                // The search key store moved to per-provider slots; attribute
                // the pre-split key to whichever provider was active first.
                if (searchKeyMigrated.compareAndSet(false, true)) {
                    keys.migrateLegacySearchKey(it.webSearchProvider)
                }
                webSearchProvider.set(it.webSearchProvider)
                disabledSearchProviders.set(it.disabledSearchProviders)
            }
        }
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            workspace.current.collect { fs ->
                projectSkillsDir = fs.shellRoot?.resolve(".harness/skills")
            }
        }
        // Regenerate the linker shims the moment the environment is ready: an
        // older format (broken argv[0] for coreutils applets) must not survive
        // an app update, and the header check makes this a no-op when current.
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            linuxEnv.state.collect {
                if (it is com.ylprivate.agents.data.env.EnvState.Ready) linuxEnv.ensureShims()
            }
        }
        // Eagerly deploy the shell-user toolchain copy the moment both the
        // Linux environment and Shizuku are ready, so every tier works from
        // the first command (foreground shell, background spawn, terminal).
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // include serviceState so a retry fires once the user service binds
            combine(linuxEnv.state, shizuku.state, shizuku.serviceState) { e, s, v -> Triple(e, s, v) }
                .collect { (e, s, v) ->
                    if (e is com.ylprivate.agents.data.env.EnvState.Ready &&
                        s == com.ylprivate.agents.data.env.ShizukuState.GRANTED &&
                        v.isReady
                    ) {
                        linuxEnv.ensureShellDeploy(shizuku)
                    }
                }
        }
        // Self-heal prefixes installed by older builds: relink dangling
        // termux-absolute symlinks, rewrite Termux shebangs, resume installs
        // that were interrupted and reinstall packages whose binaries
        // vanished. Silent when the prefix is already healthy; network
        // failures never demote a Ready environment (retried next launch).
        kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val repaired = linuxEnv.repairIfNeeded()
            // A repair changes prefix content (reinstalled pip, rewritten
            // shebangs) without necessarily changing the package-set hash, and
            // StateFlow conflates the unchanged Ready state so the deploy
            // collector above never re-fires, re-stage the shell-tier copy
            // explicitly so it picks the fixes up.
            if (repaired != null &&
                shizuku.state.value == com.ylprivate.agents.data.env.ShizukuState.GRANTED &&
                shizuku.serviceState.value.isReady
            ) {
                linuxEnv.ensureShellDeploy(shizuku)
            }
        }
    }
}
