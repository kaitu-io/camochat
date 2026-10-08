package app.chencang.android.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.chencang.android.BuildConfig
import app.chencang.android.MainActivity
import app.chencang.android.clipboard.AppClipboard
import app.chencang.android.share.ApkShare
import app.chencang.android.share.PairingShareEvents
import app.chencang.android.share.PairingShareReceiver
import app.chencang.android.ui.about.AboutScreen
import app.chencang.android.ui.chat.AndroidMediaPreparer
import app.chencang.android.ui.chat.ConversationListContent
import app.chencang.android.ui.chat.ConversationListViewModel
import app.chencang.android.ui.chat.ConversationScreen
import app.chencang.android.ui.chat.ConversationViewModel
import app.chencang.android.ui.contact.ContactListContent
import app.chencang.android.ui.contact.ContactListViewModel
import app.chencang.android.ui.contact.ContactScreen
import app.chencang.android.ui.contact.ContactViewModel
import app.chencang.android.ui.intake.IntakeNoticeDialog
import app.chencang.android.ui.main.MainScreen
import app.chencang.android.ui.main.rememberPasteBarState
import app.chencang.android.ui.me.MeContent
import app.chencang.android.ui.me.MyProfileSection
import app.chencang.android.ui.onboarding.OnboardingScreen
import app.chencang.android.ui.onboarding.OnboardingViewModel
import app.chencang.android.ui.pairing.PairingWizardScreen
import app.chencang.android.ui.pairing.PairingWizardViewModel
import app.chencang.android.ui.pairing.QrScanScreen
import app.chencang.android.ui.pairing.WizardEntry
import app.chencang.android.ui.pairing.WizardStage
import app.chencang.android.ui.pairing.clipboardText
import app.chencang.android.ui.pairing.launchPairingShare
import app.chencang.android.ui.recovery.RecoveryExportScreen
import app.chencang.android.ui.recovery.RecoveryRestoreScreen
import app.chencang.android.ui.settings.AboutLinks
import app.chencang.android.ui.settings.SettingsSections
import app.chencang.android.ui.share.ShareAppDialog
import app.chencang.android.update.ManualCheckResult
import app.chencang.android.update.NoUpdateController
import app.chencang.android.update.UpdateController
import app.chencang.design.Moyu
import app.chencang.shared.CcServiceLocator
import app.chencang.shared.R
import app.chencang.shared.intake.IntakeFailure
import app.chencang.shared.intake.IntakeOutcome
import app.chencang.shared.pairing.inband.PendingItem
import app.chencang.shared.pairing.inband.PendingKind
import app.chencang.shared.uat.UatLoopback
import kotlinx.coroutines.launch

object Routes {
    const val ONBOARDING = "onboarding"
    /** 三个顶层 tab 的唯一宿主：底栏长在这一屏里面，其它路由因此没有底栏。 */
    const val MAIN = "main"
    /** `role` ∈ `initiator | redeemer | resume-invite | resume-response`；`id` 给两种恢复入口用。 */
    const val PAIRING_WIZARD = "pairing/{role}?id={id}"
    const val PAIRING_SCAN = "pairingScan"
    const val CONTACT = "contact/{fingerprintHex}"
    const val CONVERSATION = "conversation/{peerUsername}"
    const val RECOVERY_EXPORT = "recoveryExport"
    const val RECOVERY_RESTORE = "recoveryRestore"
    const val ABOUT = "about"

    fun contactRoute(fingerprintHex: String) = "contact/$fingerprintHex"
    fun conversationRoute(peerUsername: String) = "conversation/${Uri.encode(peerUsername)}"
    fun pairingWizardRoute(role: String, id: String? = null) =
        "pairing/$role" + (id?.let { "?id=${Uri.encode(it)}" } ?: "")
}

@Composable
fun CcNavGraph(
    locator: CcServiceLocator,
    startDestination: String,
    launch: LaunchRequest = LaunchRequest.None,
    updates: UpdateController = NoUpdateController(),
) {
    val nav = rememberNavController()

    // The request is kept (as a string) together with a "carried out" flag: a rotation during
    // onboarding rebuilds this with launch = None (the extras were consumed), and a process-death
    // restore hands the original request back — neither may lose it or replay it.
    val pendingLaunch = rememberSaveable { launch.encode() }
    var launchHandled by rememberSaveable { mutableStateOf(false) }
    fun runLaunch(steps: List<NavStep>) {
        launchHandled = true
        steps.forEach { step -> nav.execute(step) }
    }
    // Starting on main: carry it out at once. Starting on onboarding: its onDone does (below).
    LaunchedEffect(Unit) {
        if (startDestination == Routes.MAIN && !launchHandled) {
            runLaunch(plan(LaunchRequest.decode(pendingLaunch)))
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 粘贴条状态与分流全图共用：会话/联系人 tab 和对话页都走同一个 routePastedText。
    val pasteBar = rememberPasteBarState()
    // 粘贴失败的提示；NO_CONTACTS 提供「添加联系人」，其余只有「好」。
    var intakeNotice by rememberSaveable { mutableStateOf<IntakeFailure?>(null) }
    val addContact = { nav.navigate(Routes.pairingWizardRoute("initiator")) }
    intakeNotice?.let { failure ->
        IntakeNoticeDialog(
            failure = failure,
            onAddContact = { intakeNotice = null; addContact() },
            onDismiss = { intakeNotice = null },
        )
    }
    /** [text] 为 null = 没读到；有文本却读不到（判定时 hasText 为 true）提示去输入框长按粘贴。 */
    fun routePastedText(text: String?) {
        if (text == null) {
            val res = if (AppClipboard.hasText(context)) R.string.paste_bar_read_failed else R.string.intake_error_clipboard_empty
            Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            when (val outcome = pasteIntake(locator, text)) {
                is IntakeOutcome.OpenThread -> nav.openThreadOnChats(outcome.peerUsername)
                is IntakeOutcome.AlreadyInThread -> {
                    Toast.makeText(context, R.string.intake_already_in_thread, Toast.LENGTH_SHORT).show()
                    nav.openThreadOnChats(outcome.peerUsername)
                }
                is IntakeOutcome.Pairing -> nav.openWizardWithWire(outcome.wire)
                // 不是陈仓的内容 / 只复制到了链接：轻提示即可，不弹框。
                is IntakeOutcome.Failed -> when (outcome.failure) {
                    IntakeFailure.NOT_OURS ->
                        Toast.makeText(context, R.string.paste_bar_not_ours, Toast.LENGTH_SHORT).show()
                    IntakeFailure.LINK_ONLY ->
                        Toast.makeText(context, R.string.paste_bar_link_only, Toast.LENGTH_LONG).show()
                    else -> intakeNotice = outcome.failure
                }
            }
        }
    }
    val onPasteBarPaste = { routePastedText(clipboardText(context)) }

    NavHost(
        navController = nav,
        startDestination = startDestination,
        enterTransition = { fadeIn(tween(Moyu.Motion.Standard, delayMillis = Moyu.Motion.Quick / 2)) },
        exitTransition = { fadeOut(tween(Moyu.Motion.Quick)) },
        popEnterTransition = { fadeIn(tween(Moyu.Motion.Standard, delayMillis = Moyu.Motion.Quick / 2)) },
        popExitTransition = { fadeOut(tween(Moyu.Motion.Quick)) },
    ) {
        composable(Routes.ONBOARDING) {
            val vm: OnboardingViewModel = viewModel(factory = onboardingFactory(locator))
            // Onboarding's 幕 2「我明白了」goes to the main shell (会话 tab, 空态 = 幕 3),
            // then whatever the app was opened for (a pairing code, a link, a decrypted message).
            OnboardingScreen(viewModel = vm, onDone = {
                val request = if (launchHandled) LaunchRequest.None else LaunchRequest.decode(pendingLaunch)
                runLaunch(afterOnboarding(request))
            })
        }

        composable(Routes.MAIN) { entry ->
            // 当前 tab 活在 main 条目的 savedStateHandle 里（冷启动恒为会话）；三个 tab 的 ViewModel
            // 都以该条目为 owner，tab 来回切不重建。底栏长在 main 这一屏里面，其它路由不可能出现它。
            val tab by entry.savedStateHandle
                .getStateFlow(MAIN_TAB_KEY, MainTab.Chats.key)
                .collectAsStateWithLifecycle()
            val conversationVm: ConversationListViewModel =
                viewModel(entry, factory = conversationListFactory(locator))
            val contactListVm: ContactListViewModel =
                viewModel(entry, factory = contactListFactory(locator))
            val contactsBadge by contactListVm.badge.collectAsStateWithLifecycle()
            MainScreen(
                tab = MainTab.fromKey(tab),
                onTabSelected = { nav.setMainTab(it) },
                contactsBadge = contactsBadge,
                pasteBar = pasteBar,
                onPasteBarPaste = onPasteBarPaste,
                onAddContact = addContact,
                chats = { padding ->
                    ConversationListContent(
                        viewModel = conversationVm,
                        contentPadding = padding,
                        onOpenThread = { username -> nav.navigate(Routes.conversationRoute(username)) },
                        onAddContact = addContact,
                        onGoToContacts = { nav.setMainTab(MainTab.Contacts) },
                    )
                },
                contacts = { padding ->
                    ContactListContent(
                        viewModel = contactListVm,
                        contentPadding = padding,
                        onOpenContact = { fp -> nav.navigate(Routes.contactRoute(fp)) },
                        onAddContact = addContact,
                        onOpenPending = { item ->
                            if (item.kind == PendingKind.ResponseUnsent) {
                                nav.navigate(Routes.pairingWizardRoute("resume-response", id = item.id))
                            } else {
                                nav.navigate(Routes.pairingWizardRoute("resume-invite", id = item.id))
                            }
                        },
                        onResendPending = { item -> scope.launch { resendPending(locator, context, item) } },
                        onDeleteInvite = { id ->
                            runCatching { locator.pairingCoordinator.deleteInvite(id) }
                                .onFailure { android.util.Log.w("Chencang", "deleteInvite failed", it) }
                                .isSuccess
                        },
                    )
                },
                me = { padding ->
                    MeContent(
                        contentPadding = padding,
                        profile = { MyProfileSection(locator) },
                        settings = {
                            MeSettings(
                                locator = locator,
                                updates = updates,
                                onRecoveryExport = { nav.navigate(Routes.RECOVERY_EXPORT) },
                                onRecoveryRestore = { nav.navigate(Routes.RECOVERY_RESTORE) },
                                onOpenAbout = { nav.navigate(Routes.ABOUT) },
                            )
                        },
                    )
                },
            )
        }

        composable(
            route = Routes.CONVERSATION,
            arguments = listOf(navArgument("peerUsername") { type = NavType.StringType }),
        ) { entry ->
            // navigation-compose already Uri-decodes path args before they land
            // here (mirrors how `target`/`fingerprintHex` are read raw above) —
            // decoding again would corrupt a username containing a literal `%`.
            val peerUsername = entry.arguments?.getString("peerUsername").orEmpty()
            val appContext = LocalContext.current.applicationContext
            val vm: ConversationViewModel = viewModel(factory = conversationFactory(locator, peerUsername, appContext))
            ConversationScreen(
                peerUsername = peerUsername,
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onOpenContact = { fp -> nav.navigate(Routes.contactRoute(fp)) },
                onOpenThread = { peer -> nav.openThreadOnChats(peer) },
                onOpenWizard = { wire -> nav.openWizardWithWire(wire) },
                pasteBar = pasteBar,
                onPasteBarPaste = onPasteBarPaste,
            )
        }

        composable(
            route = Routes.PAIRING_WIZARD,
            arguments = listOf(
                navArgument("role") { type = NavType.StringType },
                navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id")
            val wizardEntry = when (entry.arguments?.getString("role")) {
                "initiator" -> WizardEntry.Initiator
                "resume-invite" -> id?.let { WizardEntry.ResumeInvite(it) } ?: WizardEntry.Redeemer
                "resume-response" -> id?.let { WizardEntry.ResumeResponse(it) } ?: WizardEntry.Redeemer
                else -> WizardEntry.Redeemer
            }
            val vm: PairingWizardViewModel = viewModel(factory = pairingWizardFactory(locator, wizardEntry))
            LaunchedEffect(vm) {
                vm.openThread.collect { username -> nav.openThreadOnChats(username) }
            }
            // 删除邀请之后向导自行关闭（关闭向导本身不删任何记录）。
            LaunchedEffect(vm) {
                vm.closed.collect { nav.popBackStack() }
            }
            // The QR-scan detour (below) and [openWizardWithWire] stash a wire on THIS entry's
            // savedStateHandle — observing it as a StateFlow funnels both (set once, then cleared)
            // through the same submitWire() call.
            val incomingWire by entry.savedStateHandle
                .getStateFlow<String?>(INCOMING_WIRE_KEY, null)
                .collectAsStateWithLifecycle()
            val wizardUi by vm.ui.collectAsStateWithLifecycle()
            // Submitted once the wizard can take it: on "enter their code" or on the send step of my own
            // invite (the stage is Working until start() ran).
            val canTakeWire = wizardUi.stage.let { it is WizardStage.Receive || (it is WizardStage.Show && !it.isResponse) }
            LaunchedEffect(incomingWire, canTakeWire) {
                val wire = incomingWire ?: return@LaunchedEffect
                if (!canTakeWire) return@LaunchedEffect
                vm.submitWire(wire)
                entry.savedStateHandle.remove<String>(INCOMING_WIRE_KEY)
            }
            PairingWizardScreen(
                viewModel = vm,
                shareSite = locator.configRepository.config.value.shareSite,
                onScanRequest = { nav.navigate(Routes.PAIRING_SCAN) },
                onBack = { nav.popBackStack() },
                onOpenContact = { fp ->
                    nav.popBackStack()
                    nav.navigate(Routes.contactRoute(fp))
                },
                // 向导里点「粘贴」同样算处理过这份剪贴板：记已消费，收起共享的粘贴条。
                onClipboardPasted = pasteBar::consume,
            )
        }

        composable(Routes.PAIRING_SCAN) {
            QrScanScreen(
                onResult = { scanned ->
                    nav.previousBackStackEntry?.savedStateHandle?.set(INCOMING_WIRE_KEY, scanned)
                    nav.popBackStack()
                },
                onCancel = { nav.popBackStack() },
            )
        }

        composable(
            route = Routes.CONTACT,
            arguments = listOf(navArgument("fingerprintHex") { type = NavType.StringType }),
        ) { entry ->
            val fingerprintHex = entry.arguments?.getString("fingerprintHex").orEmpty()
            val vm: ContactViewModel = viewModel(factory = contactFactory(locator, fingerprintHex))
            val context = LocalContext.current
            val resendScope = rememberCoroutineScope()
            // Same "navigation event collected at the nav host" split as the
            // pairing wizard's openThread above — deleteContact()'s three
            // deletes must all land before we pop, so the screen only ever
            // emits [ContactViewModel.deleted] once they have.
            LaunchedEffect(vm) {
                vm.deleted.collect {
                    nav.backToMainAfterContactDeleted()
                }
            }
            ContactScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onMessage = { vm.contact.value?.let { nav.sendMessageFromContact(it.username) } },
                // Marking it shared is left to the share sheet's callback (PairingShareReceiver).
                onResendResponse = { wire ->
                    resendScope.launch {
                        launchPairingShare(
                            context,
                            locator.configRepository.config.value.shareSite,
                            wire,
                            isResponse = true,
                            name = locator.appPrefs.myName.value,
                            kind = PairingShareReceiver.KIND_RESPONSE,
                            id = fingerprintHex,
                        )
                    }
                },
            )
        }

        composable(Routes.RECOVERY_EXPORT) {
            // Mnemonic word list comes from chencang-core via uniffi `secret.exportMnemonic()`
            // (V2 surface). Placeholder for now.
            RecoveryExportScreen(mnemonicWords = List(12) { "—" })
        }
        composable(Routes.ABOUT) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val site = locator.configRepository.config.value.shareSite
            var shareDialog by remember { mutableStateOf(false) }
            AboutScreen(
                site = site,
                versionName = BuildConfig.VERSION_NAME,
                onShareApp = { shareDialog = true },
                onOpenSource = { AboutLinks.openSource(context, site) },
                onOpenSite = { AboutLinks.openSite(context, site) },
                onOpenPrivacy = { AboutLinks.openPrivacy(context, site) },
                onBack = { nav.popBackStack() },
            )
            if (shareDialog) {
                ShareAppDialog(
                    onShareApk = { asZip -> scope.launch { ApkShare.share(context, asZip) } },
                    onDismiss = { shareDialog = false },
                )
            }
        }
        composable(Routes.RECOVERY_RESTORE) {
            RecoveryRestoreScreen(onRestore = { /* wired to IdentityStore.restore in V2 */ })
        }
    }
}

/** Carries out one [NavStep] of a [LaunchRequest]. */
private fun NavController.execute(step: NavStep) {
    when (step) {
        NavStep.ToMain -> navigate(Routes.MAIN) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
        is NavStep.ToThread -> openThreadOnChats(step.peerUsername)
        is NavStep.ToWizard ->
            if (step.role == "redeemer") {
                openWizardWithWire(step.wire)
            } else {
                navigate(Routes.pairingWizardRoute(step.role))
            }
    }
}

/** 「我」tab 里的设置分组：把 [AppPrefs] / 账号清除等接到 [SettingsSections]。 */
@Composable
private fun MeSettings(
    locator: CcServiceLocator,
    updates: UpdateController,
    onRecoveryExport: () -> Unit,
    onRecoveryRestore: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = locator.appPrefs
    val summaryPrivacy by prefs.summaryPrivacy.collectAsStateWithLifecycle()
    SettingsSections(
        summaryPrivacy = summaryPrivacy,
        onSummaryPrivacyChange = prefs::setSummaryPrivacy,
        onRecoveryExport = if (BuildConfig.DEBUG) onRecoveryExport else null,
        onRecoveryRestore = if (BuildConfig.DEBUG) onRecoveryRestore else null,
        onDeleteAccount = {
            scope.launch {
                runCatching { locator.accountWiper.wipeAll() }
                    .onFailure { android.util.Log.w("Chencang", "partial wipe", it) }
                CcServiceLocator.reset()
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    ),
                )
            }
        },
        versionName = BuildConfig.VERSION_NAME,
        onCheckForUpdate = if (updates.supportsManualCheck) ({
            scope.launch {
                val message = when (updates.checkNow()) {
                    ManualCheckResult.UP_TO_DATE -> R.string.update_check_uptodate
                    ManualCheckResult.FAILED -> R.string.update_check_failed
                    ManualCheckResult.UPDATE_AVAILABLE -> null
                }
                if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }) else null,
        onOpenSource = { AboutLinks.openSource(context, locator.configRepository.config.value.shareSite) },
        onOpenPrivacy = { AboutLinks.openPrivacy(context, locator.configRepository.config.value.shareSite) },
        onOpenAbout = onOpenAbout,
        onShareApp = { asZip -> scope.launch { ApkShare.share(context, asZip) } },
        onRunUatLoopback = if (BuildConfig.DEBUG) ({ UatLoopback.runE2E() }) else null,
        onSeedUat = if (BuildConfig.DEBUG) ({
            if (!locator.identityStore.hasIdentity()) {
                locator.identityStore.generateAndSave().close()
            }
            UatLoopback.seed(
                repository = locator.repository,
                store = locator.sessionStore,
            )
        }) else null,
    )
}

// ---- ViewModel factories ----

private fun onboardingFactory(locator: CcServiceLocator) =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            @Suppress("UNCHECKED_CAST")
            return OnboardingViewModel(
                hasIdentity = { locator.identityStore.hasIdentity() },
                generateIdentity = { locator.identityStore.generateAndSave().close() },
                namePromptDone = { locator.appPrefs.namePromptDone },
                saveName = { name -> locator.appPrefs.saveAnsweredName(name) },
                savedState = extras.createSavedStateHandle(),
            ) as T
        }
    }

private fun conversationListFactory(locator: CcServiceLocator) =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ConversationListViewModel(
                contacts = locator.repository.contacts,
                latest = locator.chatRepository.observeLatestPerPeer(),
                summaryPrivacy = locator.appPrefs.summaryPrivacy,
                failedOutgoingMedia = locator.chatRepository.observeFailedOutgoingMedia(),
                // Only a shared invite counts as pairing in progress (a reply waiting to go back already has a contact).
                invites = locator.pendingInviteStore.records,
                itemCounts = locator.chatRepository.observeAlbumSizes(),
                responses = locator.pairingResponseStore.records,
                ccaExists = locator.mediaSender::ccaExists,
            ) as T
        }
    }

private fun contactListFactory(locator: CcServiceLocator) =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ContactListViewModel(
                contacts = locator.repository.contacts,
                invites = locator.pendingInviteStore.records,
                responses = locator.pairingResponseStore.records,
            ) as T
        }
    }

private fun contactFactory(locator: CcServiceLocator, fingerprintHex: String) =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ContactViewModel(
                fingerprintHex = fingerprintHex,
                repository = locator.repository,
                chatRepository = locator.chatRepository,
                sessionStore = locator.sessionStore,
                forgetPeer = locator.pairingCoordinator::forgetPeer,
                responses = locator.pairingResponseStore.records,
            ) as T
        }
    }

private fun pairingWizardFactory(locator: CcServiceLocator, entry: WizardEntry) =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            @Suppress("UNCHECKED_CAST")
            return PairingWizardViewModel(
                entry = entry,
                pairing = locator.pairingCoordinator,
                intake = locator.incomingIntake,
                repository = locator.repository,
                chatRepository = locator.chatRepository,
                sessionStore = locator.sessionStore,
                discardScope = locator.scope,
                shareCompleted = PairingShareEvents.events,
                pendingInvites = locator.pendingInviteStore.records,
                myDisplayName = { locator.appPrefs.myName.value },
                namePromptDone = { locator.appPrefs.namePromptDone },
                saveName = { name -> locator.appPrefs.saveAnsweredName(name) },
                savedState = extras.createSavedStateHandle(),
            ) as T
        }
    }

private fun conversationFactory(locator: CcServiceLocator, peerUsername: String, appContext: Context) =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ConversationViewModel(
                repo = locator.chatRepository,
                peerUsername = peerUsername,
                contacts = locator.repository.contacts,
                sender = locator.mediaSender,
                downloader = locator.mediaDownloader,
                preparer = AndroidMediaPreparer(appContext),
                prefs = locator.appPrefs,
                intake = locator.incomingIntake,
            ) as T
        }
    }

/** Runs pasted text through the intake; a storage error reads as SAVE_FAILED (only the class name is logged). */
private suspend fun pasteIntake(locator: CcServiceLocator, text: String): IntakeOutcome = try {
    locator.incomingIntake.handle(text)
} catch (ce: kotlinx.coroutines.CancellationException) {
    throw ce
} catch (t: Throwable) {
    android.util.Log.w("Chencang", "paste intake failed: ${t.javaClass.simpleName}")
    IntakeOutcome.Failed(IntakeFailure.SAVE_FAILED)
}

/** Pending row's trailing button: the same pairing code in the share sheet; the share callback marks it shared. */
private suspend fun resendPending(locator: CcServiceLocator, context: Context, item: PendingItem) {
    val coordinator = locator.pairingCoordinator
    try {
        if (item.kind == PendingKind.ResponseUnsent) {
            val wire = coordinator.pendingResponse(item.id)?.responseWire.orEmpty()
            if (wire.isEmpty()) return
            launchPairingShare(context, locator.configRepository.config.value.shareSite, wire, true, locator.appPrefs.myName.value, PairingShareReceiver.KIND_RESPONSE, item.id)
        } else {
            val wire = coordinator.pendingInvite(item.id)?.inviteWire.orEmpty()
            if (wire.isEmpty()) return
            launchPairingShare(context, locator.configRepository.config.value.shareSite, wire, false, locator.appPrefs.myName.value, PairingShareReceiver.KIND_INVITE, item.id)
        }
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (t: Throwable) {
        android.util.Log.w("Chencang", "resendPending failed", t)
    }
}
