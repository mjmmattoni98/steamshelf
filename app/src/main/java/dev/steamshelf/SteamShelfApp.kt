package dev.steamshelf

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import kotlinx.coroutines.launch

class SteamShelfApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            // Coming back to a process that's still alive should refresh playtime too, not just a cold start.
            override fun onStart(owner: LifecycleOwner) {
                container.appScope.launch {
                    if (container.repository.shouldAutoSync()) container.repository.sync()
                }
            }

            // Android may kill a backgrounded app without warning: write pending changes now.
            override fun onStop(owner: LifecycleOwner) {
                container.appScope.launch { container.repository.flush() }
            }
        })
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).crossfade(true).build()
}
