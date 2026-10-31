package io.github.romirom11.tivibro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import io.github.romirom11.tivibro.ui.App

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    /** The app's language (Settings → «Мова / Language») over the system's. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(L10n.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vm.onExit = { finishAndRemoveTask() }
        vm.onRecreate = { recreate() }
        takePlaylist(intent)
        setContent { App(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takePlaylist(intent)
    }

    /** Debug builds only: `adb shell am start -n io.github.romirom11.tivibro/.MainActivity --es playlist '<url>'`.
     *  A release build ignores the extra, so no other app can switch the playlist through the exported activity. */
    private fun takePlaylist(i: Intent?) {
        if (!BuildConfig.DEBUG) return
        i?.getStringExtra("playlist")?.takeIf { it.startsWith("http") }?.let(vm::usePlaylist)
    }

    /** The remote is routed by hand, not by Compose focus: one owner, same rules on every screen. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = vm.onKey(event) || super.dispatchKeyEvent(event)

    override fun onStart() { super.onStart(); vm.onStart() }
    override fun onStop() { vm.onStop(); super.onStop() }
}
