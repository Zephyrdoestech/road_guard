@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wecare.roadguard.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.wecare.roadguard.BuildConfig
import com.wecare.roadguard.R
import com.wecare.roadguard.data.*
import com.wecare.roadguard.domain.*
import com.wecare.roadguard.monitoring.MonitoringState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Navy = Color(0xFF142E82)
private val Blue = Color(0xFF2457DC)
private val Yellow = Color(0xFFEAE13D)
private val Ink = Color(0xFF16203A)
private val Muted = Color(0xFF61708A)
private val Pale = Color(0xFFF3F6FC)
private val Danger = Color(0xFFBC293D)
val LocalTypingBlocked = staticCompositionLocalOf { false }

@Composable
fun RoadGuardScreen(vm: RoadGuardViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val monitoring by vm.monitoring.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(500) } }
    val interrupted = state.localTrips.firstOrNull { it.active }
    val blocked = typingBlocked(monitoring.role, monitoring.phase, now, monitoring.overrideUntil) ||
        (!monitoring.running && interrupted?.role == "Driver" && interrupted.phase == "Moving")
    val keyboard = LocalSoftwareKeyboardController.current
    val activity = LocalActivity.current
    SideEffect { activity?.let { androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView).isAppearanceLightStatusBars = state.signedIn && state.verified } }
    LaunchedEffect(blocked) { if (blocked) keyboard?.hide() }
    MaterialTheme(colorScheme = lightColorScheme(primary = Navy, secondary = Blue, tertiary = Yellow, background = Pale,
        surface = Color.White, onSurface = Ink, error = Danger), typography = Typography()) {
        CompositionLocalProvider(LocalTypingBlocked provides blocked) {
            if (!state.signedIn || !state.verified) Welcome(vm, state)
            else MainShell(vm, state, monitoring, now)
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().statusBarsPadding())
        }
    }
}

@Composable private fun Welcome(vm: RoadGuardViewModel, state: AppState) {
    var register by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Navy).statusBarsPadding().background(Pale).navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Navy, Blue))).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(painterResource(R.drawable.roadguard_logo), "RoadGuard shield and steering wheel", Modifier.size(68.dp).clip(RoundedCornerShape(18.dp)))
                Column { Text("RoadGuard", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("BY WECARE", color = Yellow, fontSize = 11.sp, letterSpacing = 3.sp) }
            }
            Spacer(Modifier.height(14.dp))
            Text("Stay focused.\nStay connected.", fontSize = 36.sp, lineHeight = 42.sp, color = Color.White, fontWeight = FontWeight.Bold)
            Text("A little more peace of mind, every time you head out.", color = Color(0xFFDCE5FF), fontSize = 17.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { FeatureLabel(Icons.Default.Shield, "Drive mindfully"); FeatureLabel(Icons.Default.People, "Keep close") }
        }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!vm.repo.configured) InfoCard("App setup needed", "This build is not connected to a Firebase project yet. Accounts, sharing, and alerts become available after setup.", Icons.Default.Info)
            if (state.signedIn && !state.verified) {
                SectionTitle("Check your inbox", "Verify ${state.email} before sharing trips or messages.")
                Button(onClick = vm::refreshUser, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text("I've verified my email") }
                TextButton(onClick = vm::resendVerification) { Text("Resend verification email") }
                TextButton(onClick = vm::signOut) { Text("Use another account") }
            } else {
                SectionTitle(if (register) "Your safer journey starts here" else "Welcome to your circle", "Sign in to share trips with the people you trust.")
                if (register) GuardedField(name, { name = it }, "Your name")
                GuardedField(email, { email = it }, "Email address")
                GuardedField(password, { password = it }, "Password", password = true)
                Button(onClick = { vm.authenticate(email, password, name, register) }, Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    enabled = vm.repo.configured && !state.busy && email.isNotBlank() && password.length >= 8 && (!register || name.isNotBlank())) {
                    Text(if (register) "Create account" else "Sign in")
                }
                TextButton(onClick = { register = !register }, Modifier.fillMaxWidth()) { Text(if (register) "Already have an account? Sign in" else "New here? Create an account") }
                if (!register) TextButton(onClick = { vm.resetPassword(email) }, enabled = email.isNotBlank() && vm.repo.configured) { Text("Forgot password?") }
            }
            Feedback(state, vm::clearError)
            Text("You control who sees your trips. Location is shared only during a trip you start. RoadGuard protects typing inside this app; it does not block other apps.", color = Muted, fontSize = 12.sp)
        }
    }
}
@Composable private fun FeatureLabel(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { Icon(icon, null, tint = Yellow, modifier = Modifier.size(18.dp)); Text(text, color = Color.White, fontSize = 12.sp) }
}

@Composable private fun MainShell(vm: RoadGuardViewModel, state: AppState, monitor: MonitoringState, now: Long) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val titles = listOf("Home", "Trusted Circle", "Messages", "History")
    val icons = listOf(Icons.Default.Home, Icons.Default.People, Icons.AutoMirrored.Filled.Chat, Icons.Default.History)
    Scaffold(containerColor = Pale, topBar = {
        TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource(R.drawable.roadguard_logo), null, Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)))
            Text("RoadGuard", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        } }, actions = { IconButton(onClick = { settings = true }) { Icon(Icons.Default.AccountCircle, "Profile and settings") } })
    }, bottomBar = {
        NavigationBar(containerColor = Color.White) { titles.forEachIndexed { index, label ->
            NavigationBarItem(selected = tab == index, onClick = { tab = index; settings = false }, icon = { Icon(icons[index], label) }, label = { Text(label, fontSize = 11.sp) })
        } }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!state.online) InfoCard("You're offline", "New alerts and messages are not delivered until a connection returns.", Icons.Default.CloudOff)
            if (LocalTypingBlocked.current) InfoCard("Driving mode is on", "Typing is paused. Use voice, or pull over safely before continuing.", Icons.Default.Shield)
            Feedback(state, vm::clearError)
            if (BuildConfig.USE_FIREBASE_EMULATORS) Text("LOCAL EMULATOR BUILD • synthetic test accounts only", color = Danger, fontSize = 12.sp)
            if (!state.cloudReady) {
                InfoCard("Finish connecting your account", "Your account is signed in. Retry to load your circle and shared trips.", Icons.Default.Sync)
                Button(onClick = vm::refreshUser, enabled = !state.busy) { Text("Retry connection") }
            }
            if (settings) Settings(vm, state) else when (tab) {
                0 -> Home(vm, state, monitor, now)
                1 -> Circle(vm, state)
                2 -> Messages(vm, state)
                3 -> History(state)
            }
        }
    }
}

@Composable private fun Home(vm: RoadGuardViewModel, state: AppState, monitor: MonitoringState, now: Long) {
    val context = LocalContext.current
    var role by rememberSaveable { mutableStateOf(TravelRole.Driver) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var impact by rememberSaveable { mutableStateOf(false) }
    val active = state.localTrips.firstOrNull { it.active }
    LaunchedEffect(state.contacts) { selected = selected.intersect(state.contacts.map { it.id }.toSet()) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val coarse = granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (coarse) { if (active != null) vm.resumeTrip() else vm.startTrip(role, selected, impact) }
        else vm.report("Location access is needed to monitor a trip. You can grant it in Android app settings.")
    }
    fun start() {
        permissions.launch(buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION); add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())
    }
    SectionTitle(if (active != null) "Your journey, protected" else "Ready for the road?", if (active != null) "Your trusted circle travels with you." else "Set up your trip before you head out.")
    Card(colors = CardDefaults.cardColors(containerColor = Navy), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Shield, null, tint = Yellow)
                Text(if (monitor.running) "TRIP MONITORING ACTIVE" else if (active != null) "MONITORING INTERRUPTED" else "YOUR ROAD COMPANION", color = Yellow, fontSize = 11.sp, letterSpacing = 1.5.sp)
            }
            Text(if (monitor.running) monitor.phase.name else if (active != null) "Resume when ready" else "Eyes on the road.\nLoved ones in the loop.", color = Color.White, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
            Text(if (monitor.running) "${monitor.role.name} mode • ${monitor.speedKmh?.toInt()?.let { "$it km/h" } ?: "Waiting for a speed fix"}" else "Start a trip to enable movement detection and optional live sharing.", color = Color(0xFFDCE5FF))
            if (monitor.degraded) Text("Location quality is low. Driving protection stays on if already active.", color = Yellow)
            monitor.problem?.let { Text(it, color = Yellow) }
        }
    }
    if (active == null) {
        state.trips.firstOrNull { it.text("ownerUid") == state.uid && it.flag("active") }?.let { remote ->
            InfoCard("An earlier trip is still open", "Recover it to resume monitoring or end sharing before starting a new trip.", Icons.Default.Restore)
            OutlinedButton(onClick = { vm.recoverTrip(remote) }) { Text("Recover interrupted trip") }
        }
        SectionTitle("I'm travelling as", "Choose your role; movement alone cannot identify the driver.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { TravelRole.entries.forEach { value ->
            FilterChip(selected = role == value, onClick = { role = value }, label = { Text(value.name) }, leadingIcon = { Icon(if (value == TravelRole.Driver) Icons.Default.DirectionsCar else Icons.Default.Person, null) })
        } }
        ContactSelection(state.contacts, selected) { selected = it }
        if (BuildConfig.INTERNAL_BUILD || BuildConfig.IMPACT_VALIDATED) {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(impact, { impact = it }); Column(Modifier.weight(1f)) {
                Text("Experimental impact alerts", fontWeight = FontWeight.SemiBold); Text("Optional • 30 seconds to cancel a suspected event. Detection is not validated.", color = Muted, fontSize = 12.sp)
            } }
        }
        InfoCard("Before you start", "Allow location for movement detection. Precise location improves accuracy. Notifications keep your circle informed; microphone access is requested only for voice features.", Icons.Default.LocationOn)
        Button(onClick = { start() }, enabled = !state.busy && state.online, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start Trip") }
        if (selected.isEmpty()) Text("No contacts selected. Your trip will not be shared and SOS will have no recipients.", color = Muted, fontSize = 12.sp)
    } else {
        if (!monitor.running) Button(onClick = { start() }, modifier = Modifier.fillMaxWidth()) { Text("Resume monitoring") }
        if (monitor.running) {
            monitor.location?.let { LocationMap(it) }
            if (monitor.impactDeadline > 0) Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE6E8))) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Possible impact detected", fontWeight = FontWeight.Bold, color = Danger)
                    Text("An alert will be queued in ${((monitor.impactDeadline - now) / 1000).coerceAtLeast(0)} seconds.")
                    Button(onClick = { vm.service("CANCEL_IMPACT") }) { Text("I'm OK — cancel") }
                    TextButton(onClick = { vm.service("IMPACT_SOS") }) { Text("Send SOS now") }
                }
            }
            SosControl { vm.service("SOS") }
            if (monitor.role == TravelRole.Driver && monitor.phase == TripPhase.Moving) {
                OutlinedButton(onClick = { vm.service("OVERRIDE") }, enabled = monitor.overrideUntil <= now, modifier = Modifier.fillMaxWidth()) {
                    Text(if (monitor.overrideUntil > now) "Override expires in ${(monitor.overrideUntil - now) / 1000}s" else "Emergency typing override • 60 seconds")
                }
            }
        }
        OutlinedButton(onClick = vm::endTrip, modifier = Modifier.fillMaxWidth()) { Text("End Trip") }
        val cloudTrip = state.trips.find { it.id == active.id }
        cloudTrip?.let { SharingEditor(vm, it, state.contacts) }
    }
    PendingCards(vm, state, alertsOnly = true)
    state.alerts.filter { it.text("status") == "open" }.forEach { AlertCard(vm, state, it) }
    val shared = state.trips.filter { it.text("ownerUid") != state.uid && it.flag("active") }
    if (shared.isNotEmpty()) SectionTitle("Your circle on the move", "Location updates are visible only to invited contacts.")
    shared.forEach { TripMap(it) }
    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL)) }.onFailure { vm.report("No dialer is available on this device.") } }) { Icon(Icons.Default.Call, null); Spacer(Modifier.width(8.dp)); Text("Open phone dialer") }
}

@Composable private fun ContactSelection(contacts: List<CloudRecord>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    Text("Share with your circle", fontWeight = FontWeight.SemiBold)
    if (contacts.isEmpty()) Text("Add a trusted contact from the Circle tab first, or travel without sharing.", color = Muted, fontSize = 13.sp)
    contacts.forEach { c -> Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(c.id in selected, { checked -> onChange(if (checked) selected + c.id else selected - c.id) }); Text(c.text("displayName"))
    } }
}
@Composable private fun SharingEditor(vm: RoadGuardViewModel, trip: CloudRecord, contacts: List<CloudRecord>) {
    var edit by remember { mutableStateOf(false) }
    @Suppress("UNCHECKED_CAST") val participants = (trip.fields["participantUids"] as? List<String>).orEmpty()
    var selection by remember(trip.id, participants) { mutableStateOf(participants.toSet().intersect(contacts.map { it.id }.toSet())) }
    TextButton(onClick = { edit = !edit }) { Text("Manage trip sharing") }
    if (edit) { ContactSelection(contacts, selection) { selection = it }; Button(onClick = { vm.shareTrip(trip.id, selection); edit = false }) { Text("Save sharing") } }
}
@Composable private fun SosControl(send: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    var confirmAccessible by remember { mutableStateOf(false) }
    Surface(color = Danger, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp)
        .semantics { contentDescription = "SOS. Hold for two seconds to alert your selected contacts."; onClick("Confirm SOS") { confirmAccessible = true; true } }
        .pointerInput(Unit) { detectTapGestures(onPress = {
            holding = true
            try { if (withTimeoutOrNull(2_000) { tryAwaitRelease() } == null) { send(); tryAwaitRelease() } }
            finally { holding = false }
        }) }) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Sos, null, Modifier.size(34.dp), tint = Color.White)
            Column { Text(if (holding) "Keep holding…" else "Hold for SOS", fontWeight = FontWeight.Bold, fontSize = 21.sp, color = Color.White); Text("2 seconds • alert your selected contacts", color = Color.White, fontSize = 12.sp) }
        }
    }
    if (confirmAccessible) AlertDialog(onDismissRequest = { confirmAccessible = false }, title = { Text("Send SOS?") }, text = { Text("Alert the contacts selected for this trip.") },
        confirmButton = { TextButton(onClick = { confirmAccessible = false; send() }) { Text("Send SOS") } }, dismissButton = { TextButton(onClick = { confirmAccessible = false }) { Text("Cancel") } })
}

@Composable private fun Circle(vm: RoadGuardViewModel, state: AppState) {
    var code by rememberSaveable { mutableStateOf("") }
    var revoke by remember { mutableStateOf<CloudRecord?>(null) }
    SectionTitle("People who have your back", "Invite someone you trust. They choose whether to join.")
    InfoCard("Sharing is always your choice", "Joining your circle does not start tracking. Select who can see each trip when you start it.", Icons.Default.Lock)
    Button(onClick = vm::createInvite, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text("Create invitation code") }
    state.inviteCode?.let { codeValue ->
        val context = LocalContext.current
        Card { Column(Modifier.padding(18.dp)) { Text("YOUR INVITATION • EXPIRES IN 24 HOURS", fontSize = 11.sp, color = Muted); SelectionContainerCompat(codeValue)
            TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Join my RoadGuard trusted circle with code $codeValue. Expires in 24 hours."), "Share invitation")) }) { Text("Share code") }
        } }
    }
    GuardedField(code, { code = it }, "Enter an invitation code")
    OutlinedButton(onClick = { vm.acceptInvite(code) }, enabled = code.isNotBlank() && !LocalTypingBlocked.current) { Text("Accept invitation") }
    SectionTitle("Your trusted circle", "${state.contacts.size} accepted contacts")
    if (state.contacts.isEmpty()) EmptyState(Icons.Default.People, "Better together", "Your accepted contacts will appear here.")
    state.contacts.forEach { contact -> Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.AccountCircle, null, tint = Blue, modifier = Modifier.size(38.dp)); Spacer(Modifier.width(12.dp))
        Text(contact.text("displayName"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        IconButton(onClick = { revoke = contact }) { Icon(Icons.Default.PersonRemove, "Remove ${contact.text("displayName")}") }
    } } }
    revoke?.let { contact -> AlertDialog(onDismissRequest = { revoke = null }, title = { Text("Remove this contact?") }, text = { Text("This stops their future access to shared trips and messages. Information already seen cannot be recalled.") },
        confirmButton = { TextButton(onClick = { vm.revokeContact(contact.id); revoke = null }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { revoke = null }) { Text("Keep contact") } }) }
}
@Composable private fun SelectionContainerCompat(text: String) { androidx.compose.foundation.text.selection.SelectionContainer { Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(vertical = 12.dp)) } }

@Composable private fun Messages(vm: RoadGuardViewModel, state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var receiver by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf("en-PH") }
    var status by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var voiceDraft by remember { mutableStateOf<Pair<java.io.File, Long>?>(null) }
    val voice = remember { VoiceController(context) { status = it } }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(voice, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { voice.stop(); recording = false } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); voice.close(); voiceDraft?.first?.delete() }
    }
    LaunchedEffect(state.contacts) { if (state.contacts.none { it.id == receiver }) { receiver = ""; voice.stop(); voiceDraft?.first?.delete(); voiceDraft = null } }
    var pendingVoiceAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingVoiceAction?.invoke() else status = "Microphone permission is needed for voice features."
        pendingVoiceAction = null
    }
    fun withMicrophone(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingVoiceAction = action; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    SectionTitle("A voice away", "Send a message to someone in your trusted circle.")
    if (state.contacts.isEmpty()) EmptyState(Icons.AutoMirrored.Filled.Chat, "Your first conversation awaits", "Add a trusted contact to exchange messages and voice notes.")
    state.contacts.forEach { contact -> FilterChip(receiver == contact.id, onClick = {
        voice.stop(); recording = false; voiceDraft?.first?.delete(); voiceDraft = null; receiver = contact.id; draft = ""
    }, label = { Text(contact.text("displayName")) }) }
    if (receiver.isNotEmpty()) {
        val conversation = state.messages.filter { it.text("senderUid") == receiver || it.text("receiverUid") == receiver }
        conversation.forEach { message ->
            val outgoing = message.text("senderUid") == state.uid
            Card(colors = CardDefaults.cardColors(containerColor = if (outgoing) Color(0xFFE6EDFF) else Color.White)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (outgoing) "You" else "${state.contacts.find { it.id == receiver }?.text("displayName")}", fontSize = 12.sp, color = Muted)
                    if (message.text("kind") == "text") {
                        Text(message.text("text"))
                        LaunchedEffect(message.id) { vm.markRead(message) }
                    } else OutlinedButton(onClick = { scope.launch {
                        runCatching { vm.repo.audioFile(message.text("audioPath")) }.onSuccess { file -> voice.play(file) { vm.markRead(message) } }.onFailure { status = "This voice note is unavailable or access was revoked." }
                    } }) { Icon(Icons.Default.PlayArrow, null); Text("Voice note • ${message.number("durationMs") / 1000}s") }
                    Text("${formatTime(message.number("occurredAt"))} • ${if (message.number("readAt") > 0) "Read" else "Sent"}", fontSize = 11.sp, color = Muted)
                }
            }
        }
        GuardedField(draft, { draft = it }, if (LocalTypingBlocked.current) "Typing paused while driving" else "Your message", singleLine = false)
        Button(onClick = { vm.sendText(receiver, draft); draft = "" }, enabled = draft.isNotBlank() && !LocalTypingBlocked.current && !state.busy) { Text("Send message") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(language == "en-PH", { language = "en-PH" }, label = { Text("English") }); FilterChip(language == "fil-PH", { language = "fil-PH" }, label = { Text("Filipino") }) }
        Text("Language support depends on the speech service installed on your phone.", fontSize = 12.sp, color = Muted)
        OutlinedButton(onClick = { val target = receiver; withMicrophone { voice.dictate(language, { draft = it }, { vm.sendText(target, it, voiceConfirmed = true); draft = "" }) } }, enabled = !recording) {
            Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Dictate & confirm by voice")
        }
        OutlinedButton(onClick = {
            if (recording) { voice.finishRecording(); recording = false }
            else withMicrophone { voice.record { file, duration -> voiceDraft?.first?.delete(); voiceDraft = file to duration; recording = false }; recording = true }
        }) { Icon(if (recording) Icons.Default.Stop else Icons.Default.GraphicEq, null); Spacer(Modifier.width(8.dp)); Text(if (recording) "Stop recording" else "Record a voice note") }
        voiceDraft?.let { (file, duration) -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.sendVoice(receiver, file, duration); voiceDraft = null }) { Text("Send voice note") }
            TextButton(onClick = { file.delete(); voiceDraft = null }) { Text("Discard") }
        } }
        if (status.isNotBlank()) Text(status, color = Muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(onClick = { voice.stop(); recording = false }) { Text("Stop voice session") }
    }
    PendingCards(vm, state, alertsOnly = false)
}

@Composable private fun PendingCards(vm: RoadGuardViewModel, state: AppState, alertsOnly: Boolean) {
    state.outbox.filter { if (alertsOnly) it.action == "createAlert" || it.action == "resolveAlert" else it.action == "sendMessage" }.forEach { item ->
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3D6))) { Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(if (item.action == "sendMessage") "Message ${item.status}" else "SOS update not delivered", fontWeight = FontWeight.Bold)
            if (item.action == "sendMessage") {
                val preview = runCatching { org.json.JSONObject(item.payload).optString("text", "Voice note") }.getOrDefault("Message draft")
                Text(preview, fontSize = 13.sp)
            }
            Text("Created ${formatTime(item.createdAt)}. ${if (item.status == "failed") item.error.orEmpty() else "Waiting for server acceptance."}", fontSize = 12.sp)
            Row { if (item.status == "failed") TextButton(onClick = { vm.retry(item.operationId) }) { Text("Retry") }; TextButton(onClick = { vm.discard(item) }) { Text("Discard unsent") } }
        } }
    }
}
@Composable private fun AlertCard(vm: RoadGuardViewModel, state: AppState, alert: CloudRecord) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE8EB))) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (alert.text("kind") == "manual") "SOS • ${alert.text("ownerName")}" else "Suspected impact • ${alert.text("ownerName")}", color = Danger, fontWeight = FontWeight.Bold)
        Text("${formatTime(alert.number("occurredAt"))}${if (alert.flag("delayed")) " • Delivered after a delay" else ""}", fontSize = 12.sp)
        val acks = (alert.fields["acknowledgments"] as? Map<*, *>).orEmpty()
        Text("Server accepted • ${acks.size} contact acknowledgments", fontSize = 13.sp)
        @Suppress("UNCHECKED_CAST") val recipients = (alert.fields["participantUids"] as? List<String>).orEmpty().filter { it != alert.text("ownerUid") }
        if (recipients.isEmpty()) Text("No recipients were available for this alert.", color = Danger)
        if (alert.text("ownerUid") == state.uid) Row { TextButton(onClick = { vm.resolve(alert.id, false) }) { Text("I'm safe • resolve") }; TextButton(onClick = { vm.resolve(alert.id, true) }) { Text("Cancel alert") } }
        else OutlinedButton(onClick = { vm.acknowledge(alert.id) }, enabled = !acks.containsKey(state.uid)) { Text(if (acks.containsKey(state.uid)) "Acknowledged" else "Acknowledge alert") }
        @Suppress("UNCHECKED_CAST") val point = alert.fields["location"] as? Map<String, Any>
        if (point == null) Text("No location was available when this alert was created.", fontSize = 12.sp) else LocationMap(point)
    } }
}
@Composable private fun TripMap(trip: CloudRecord) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(trip.text("ownerName"), fontWeight = FontWeight.Bold)
        Text("${trip.text("role")} • ${trip.text("phase")}", color = Muted)
        @Suppress("UNCHECKED_CAST") val point = trip.fields["latestLocation"] as? Map<String, Any>
        if (point != null) LocationMap(point) else Text("Waiting for the first location update.", color = Muted)
    } }
}
@Composable private fun LocationMap(point: Map<String, Any>) {
    val lat = (point["lat"] as? Number)?.toDouble() ?: return
    val lng = (point["lng"] as? Number)?.toDouble() ?: return
    val capturedAt = (point["capturedAt"] as? Number)?.toLong() ?: 0
    var wallNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(capturedAt) { while (true) { wallNow = System.currentTimeMillis(); delay(1000) } }
    val stale = wallNow - capturedAt > 60_000
    Text("${if (stale) "STALE LOCATION • " else ""}Updated ${formatTime(capturedAt)} • ±${(point["accuracy"] as? Number)?.toInt() ?: 0} m", fontSize = 12.sp, color = if (stale) Danger else Muted)
    if (BuildConfig.MAPS_CONFIGURED) {
        val position = LatLng(lat, lng)
        val camera = rememberCameraPositionState { this.position = CameraPosition.fromLatLngZoom(position, 15f) }
        LaunchedEffect(lat, lng) { camera.position = CameraPosition.fromLatLngZoom(position, 15f) }
        GoogleMap(modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)), cameraPositionState = camera,
            uiSettings = MapUiSettings(zoomControlsEnabled = false, scrollGesturesEnabled = !LocalTypingBlocked.current)) {
            Marker(state = rememberUpdatedMarkerState(position), title = if (stale) "Last known location" else "Shared location")
        }
    } else Text("Map is unavailable in this build. Location: %.5f, %.5f".format(lat, lng), fontSize = 12.sp)
}

@Composable private fun History(state: AppState) {
    SectionTitle("Every journey, in one place", "Trip records are retained for 30 days.")
    if (state.localTrips.isEmpty() && state.trips.isEmpty()) EmptyState(Icons.Default.Route, "Your road story starts here", "Completed trips will appear after your first journey.")
    state.localTrips.forEach { trip -> Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${trip.role} trip", fontWeight = FontWeight.Bold); Text(formatTime(trip.startedAt), color = Muted)
        Text(if (trip.active && trip.interrupted) "Interrupted • resume from Home" else trip.phase)
    } } }
    state.alerts.filter { it.text("status") != "open" }.forEach { alert -> Text("${alert.text("kind")} • ${alert.text("status")} • ${formatTime(alert.number("occurredAt"))}", fontSize = 13.sp) }
}
@Composable private fun Settings(vm: RoadGuardViewModel, state: AppState) {
    var delete by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current
    SectionTitle("Your account", state.email)
    InfoCard("You own your sharing choices", "Remove contacts or change trip recipients at any time. Server access ends when you revoke it. Someone may retain information they already saw.", Icons.Default.PrivacyTip)
    Text("Trip locations, messages, and audio expire after 30 days; server cleanup runs daily. Device diagnostics do not include message content, recordings, or precise locations.", color = Muted, fontSize = 13.sp)
    OutlinedButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }) { Text("Review Android permissions") }
    OutlinedButton(onClick = vm::signOut) { Text("Sign out") }
    TextButton(onClick = { delete = true }, enabled = !LocalTypingBlocked.current) { Text("Delete account and data", color = Danger) }
    if (state.outbox.isNotEmpty()) { SectionTitle("Pending operations", "Retry failures or discard unsent work before signing out.")
        state.outbox.forEach { op -> Row(verticalAlignment = Alignment.CenterVertically) { Text("${op.action} • ${op.status}", Modifier.weight(1f), fontSize = 12.sp); TextButton(onClick = { vm.retry(op.operationId) }) { Text("Retry") }; TextButton(onClick = { vm.discard(op) }) { Text("Discard") } } }
    }
    Text("RoadGuard ${BuildConfig.VERSION_NAME}\nPilot build • impact detection is experimental. SOS alerts trusted contacts and does not dispatch emergency services.", color = Muted, fontSize = 12.sp)
    if (delete) AlertDialog(onDismissRequest = { delete = false; password = "" }, title = { Text("Delete your RoadGuard account?") }, text = { Column {
        Text("This permanently removes your account, owned trips, messages, and recordings. Enter your password to confirm.")
        GuardedField(password, { password = it }, "Password", password = true)
    } }, confirmButton = { TextButton(onClick = { vm.deleteAccount(password); delete = false; password = "" }, enabled = password.isNotBlank()) { Text("Delete permanently", color = Danger) } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("Keep account") } })
}

@Composable fun GuardedField(value: String, onChange: (String) -> Unit, label: String, password: Boolean = false, singleLine: Boolean = true) {
    val blocked = LocalTypingBlocked.current
    OutlinedTextField(value, { if (!blocked) onChange(it) }, enabled = !blocked, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp), singleLine = singleLine, visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None)
}
@Composable private fun SectionTitle(title: String, subtitle: String) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Ink); Text(subtitle, fontSize = 14.sp, color = Muted)
} }
@Composable private fun InfoCard(title: String, text: String, icon: ImageVector) {
    Surface(color = Color(0xFFE7EDFC), shape = RoundedCornerShape(16.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = Navy, modifier = Modifier.size(22.dp)); Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = Navy); Text(text, fontSize = 13.sp, color = Muted)
        }
    } }
}
@Composable private fun EmptyState(icon: ImageVector, title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = Blue, modifier = Modifier.size(48.dp)); Text(title, fontWeight = FontWeight.SemiBold); Text(description, color = Muted, fontSize = 13.sp)
    }
}
@Composable private fun Feedback(state: AppState, clear: () -> Unit) {
    (state.error ?: state.notice)?.let { message -> Surface(color = if (state.error != null) Color(0xFFFFE8EB) else Color(0xFFE0F3E8), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(message, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 13.sp); IconButton(onClick = clear) { Icon(Icons.Default.Close, "Dismiss message") } }
    } }
}
private fun formatTime(millis: Long): String = if (millis == 0L) "Unknown time" else SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(millis))
