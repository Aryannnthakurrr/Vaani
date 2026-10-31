package com.saathi.app.ui

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.saathi.app.core.agent.AgentService
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.core.voice.tr
import com.saathi.app.features.inbox.NotifListener

/** One onboarding step: what it is for (spoken), how to check it, and where to send the user. */
data class PermissionStep(
    val id: String,
    val emoji: String,
    val title: (ReplyStyle) -> String,
    val why: (ReplyStyle) -> String,
    val granted: (Context) -> Boolean,
    /** Runtime permissions are requested in-app; everything else opens a settings page. */
    val runtime: List<String> = emptyList(),
    val settingsIntent: ((Context) -> Intent)? = null,
)

object Permissions {
    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun accessibilityOn(ctx: Context): Boolean {
        if (AgentService.instance != null) return true
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(ComponentName(ctx, AgentService::class.java).flattenToString())
    }

    fun notificationListenerOn(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(ComponentName(ctx, NotifListener::class.java).flattenToString())
    }

    private fun appDetails(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    // A row shows ✓ only when every permission it asks for was given, not just the first few.
    private val smsCalls = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_NUMBERS)
    private val cameraCalendar = listOf(Manifest.permission.CAMERA, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    val steps: List<PermissionStep> = buildList {
        add(PermissionStep("mic", "🎤", { tr(it, "Microphone", "माइक्रोफ़ोन", "Microphone") },
            { tr(it, "So you can talk to me.", "ताकि आप मुझसे बात कर सकें।", "Taaki aap mujhse baat kar sakein.") },
            { has(it, Manifest.permission.RECORD_AUDIO) }, runtime = listOf(Manifest.permission.RECORD_AUDIO)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(PermissionStep("notify", "🔔", { tr(it, "Notifications", "सूचनाएँ", "Notifications") },
                { tr(it, "So I can remind you about bills and warn you about scams.", "ताकि मैं आपको बिल याद दिला सकूँ और धोखे से बचा सकूँ।",
                    "Taaki main bills yaad dila sakoon aur scam se bacha sakoon.") },
                { has(it, Manifest.permission.POST_NOTIFICATIONS) }, runtime = listOf(Manifest.permission.POST_NOTIFICATIONS)))
        }
        add(PermissionStep("sms_calls", "🛡️", { tr(it, "Messages and calls", "संदेश और कॉल", "Messages aur calls") },
            { tr(it, "So I can spot scam messages and warn you during a suspicious call.", "ताकि मैं धोखे वाले संदेश पहचान सकूँ और संदिग्ध कॉल पर आपको सावधान कर सकूँ।",
                "Taaki main scam messages pehchaan sakoon aur suspicious call par aapko savdhaan kar sakoon.") },
            { ctx -> smsCalls.all { has(ctx, it) } }, runtime = smsCalls))
        add(PermissionStep("camera_cal", "📷", { tr(it, "Camera and calendar", "कैमरा और कैलेंडर", "Camera aur calendar") },
            { tr(it, "So I can read product labels and save your doctor visits.", "ताकि मैं सामान के लेबल पढ़ सकूँ और डॉक्टर की मुलाक़ात सेव कर सकूँ।",
                "Taaki main labels padh sakoon aur doctor appointments save kar sakoon.") },
            { ctx -> cameraCalendar.all { has(ctx, it) } }, runtime = cameraCalendar))
        add(PermissionStep("location", "📍", { tr(it, "Your area", "आपका इलाक़ा", "Aapka area") },
            { tr(it, "So I can find places and hospitals near you, and tell an ambulance where you are in an emergency.",
                "ताकि मैं आपके पास की जगहें और अस्पताल ढूँढ सकूँ, और इमरजेंसी में एम्बुलेंस को बता सकूँ कि आप कहाँ हैं।",
                "Taaki main aapke paas ki jagah aur hospital dhoondh sakoon, aur emergency mein ambulance ko bata sakoon ki aap kahan hain.") },
            { has(it, Manifest.permission.ACCESS_COARSE_LOCATION) },
            runtime = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)))
        add(PermissionStep("accessibility", "🤝", { tr(it, "Let Saathi help in other apps", "साथी को दूसरे ऐप्स में मदद करने दें", "Saathi ko doosre apps mein madad karne dein") },
            { tr(it, "Open Accessibility, find Saathi and turn it on. If it is grey, first allow restricted settings.",
                "एक्सेसिबिलिटी खोलिए, साथी ढूँढिए और चालू कीजिए। अगर बटन धुंधला है, तो पहले प्रतिबंधित सेटिंग की अनुमति दीजिए।",
                "Accessibility kholiye, Saathi dhoondhiye aur on kijiye. Agar button grey hai, to pehle restricted settings allow kijiye.") },
            ::accessibilityOn, settingsIntent = { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) }))
        add(PermissionStep("notif_listener", "📬", { tr(it, "Read my notifications", "मेरी सूचनाएँ पढ़ें", "Meri notifications padhein") },
            { tr(it, "So I can tell you about bills and deliveries. Codes and OTPs never leave your phone.",
                "ताकि मैं बिल और डिलीवरी के बारे में बता सकूँ। कोड और ओटीपी कभी फ़ोन से बाहर नहीं जाते।",
                "Taaki main bills aur delivery ke baare mein bata sakoon. OTP kabhi phone se bahar nahi jaate.") },
            ::notificationListenerOn, settingsIntent = { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }))
        add(PermissionStep("overlay", "🎙️", { tr(it, "Show the Saathi button", "साथी बटन दिखाएँ", "Saathi button dikhayein") },
            { tr(it, "So the talk button stays on top of every app.", "ताकि बात करने वाला बटन हर ऐप के ऊपर दिखे।", "Taaki baat karne wala button har app ke upar dikhe.") },
            { Settings.canDrawOverlays(it) },
            settingsIntent = { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${it.packageName}")) }))
        add(PermissionStep("battery", "🔋", { tr(it, "Keep Saathi running", "साथी को चालू रखें", "Saathi ko chalu rakhein") },
            { tr(it, "So the phone does not stop me while I watch for scams.", "ताकि फ़ोन मुझे बंद न करे।", "Taaki phone mujhe band na kare.") },
            { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
            settingsIntent = { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}")) }))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(PermissionStep("alarms", "⏰", { tr(it, "Exact reminders", "सही समय पर रिमाइंडर", "Sahi time par reminder") },
                { tr(it, "So reminders and the morning check-in come on time.", "ताकि रिमाइंडर समय पर आएँ।", "Taaki reminders time par aayein.") },
                { it.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() },
                settingsIntent = { Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${it.packageName}")) }))
        }
    }

    /** "App info → ⋮ → Allow restricted settings" on Android 13+ for sideloaded builds. */
    fun restrictedSettingsIntent(ctx: Context) = appDetails(ctx)

    /** App info, where a permission Android no longer asks about can be turned on by hand. */
    fun appSettingsIntent(ctx: Context) = appDetails(ctx)

    /**
     * Opens a settings page. Some phones don't have every page (battery, exact alarms), so it falls back to
     * App info and then to the main Settings screen instead of crashing. False when nothing could be opened.
     */
    fun open(ctx: Context, intent: Intent): Boolean = listOf(intent, appDetails(ctx), Intent(Settings.ACTION_SETTINGS)).any { i ->
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.isSuccess
    }

    fun allGranted(ctx: Context) = steps.all { it.granted(ctx) }
}
