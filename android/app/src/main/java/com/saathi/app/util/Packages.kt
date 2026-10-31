package com.saathi.app.util

/** Every third-party package name Saathi knows about lives here (SPEC §13). */
object Packages {
    const val SELF = "com.saathi.app"

    const val UBER = "com.ubercab"
    const val AMAZON = "in.amazon.mShop.android.shopping"
    const val FLIPKART = "com.flipkart.android"
    const val PRACTO = "com.practo.fabric"
    /** House-help app chosen for the build: Pronto (see NOTES.md). */
    const val HOUSE_HELP = "com.company.pronto"
    const val GOOGLE_CALENDAR = "com.google.android.calendar"
    const val SETTINGS = "com.android.settings"

    const val WHATSAPP = "com.whatsapp"
    const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"
    const val GMAIL = "com.google.android.gm"

    /** Apps the agent may open/tap in ACT mode. GUIDE mode works anywhere because it never taps. */
    val ACT_ALLOWLIST = setOf(SELF, UBER, AMAZON, FLIPKART, PRACTO, HOUSE_HELP, GOOGLE_CALENDAR, SETTINGS)

    /** Money apps: opening one during a call with a stranger raises the risk score. */
    val MONEY_APPS = setOf(
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp", // BHIM
        "com.sbi.lotusintouch", // YONO SBI
        "com.sbi.SBIFreedomPlus",
        "com.csam.icici.bank.imobile",
        "com.snapwork.hdfc",
        "com.axis.mobile",
        "com.msf.kbank.mobile", // Kotak
        "com.dreamplug.androidapp", // CRED
        "in.amazon.mShop.android.shopping.pay",
    )

    /** Remote-access / screen-sharing apps, the classic "support" scam tool. */
    val REMOTE_ACCESS_APPS = setOf(
        "com.anydesk.anydeskandroid",
        "com.teamviewer.quicksupport.market",
        "com.teamviewer.teamviewer.market.mobile",
        "com.carriez.flutter_hbb", // RustDesk
        "com.airdroid.android.airmirror",
    )

    /** Sources whose notifications feed the inbox. Bank/bill apps are matched by [MONEY_APPS] too. */
    val INBOX_SOURCES = setOf(WHATSAPP, GOOGLE_MESSAGES, GMAIL) + MONEY_APPS

    fun displayName(pkg: String): String = when (pkg) {
        SELF -> "Saathi"
        UBER -> "Uber"
        AMAZON -> "Amazon"
        FLIPKART -> "Flipkart"
        PRACTO -> "Practo"
        HOUSE_HELP -> "Pronto"
        GOOGLE_CALENDAR -> "Google Calendar"
        SETTINGS -> "Settings"
        WHATSAPP -> "WhatsApp"
        else -> pkg.substringAfterLast('.')
    }
}
