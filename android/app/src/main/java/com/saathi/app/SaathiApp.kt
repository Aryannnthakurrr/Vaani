package com.saathi.app

import android.app.Application
import com.saathi.app.core.AppContainer
import kotlinx.coroutines.launch

class SaathiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        instance = this
        container.notifier.createChannels()
        container.scope.launch {
            container.settings.ensureDeviceId()
            container.welfare.scheduleDailyPrompt()
            container.digest.scheduleMorningDigest()
            // No Sarvam key (or no backend) → skip cloud STT and go straight to the on-device recognizer.
            container.assistant.speech.cloudSpeech = runCatching { container.backend.health().sarvam }.getOrDefault(false)
        }
    }

    companion object {
        lateinit var instance: SaathiApp
            private set

        val container: AppContainer get() = instance.container
    }
}
