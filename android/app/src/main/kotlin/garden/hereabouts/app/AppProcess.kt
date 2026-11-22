// What lasts as long as the app's process: the model, the phone's signal and the data check. The screen reaches them
// through its ViewModel, and a tapped reminder reaches them before the screen has drawn, so both see the same model.
package garden.hereabouts.app

import android.app.Application
import android.content.Context
import android.os.Looper
import garden.hereabouts.app.services.Connectivity
import garden.hereabouts.app.services.DataUpdater
import garden.hereabouts.app.services.ServiceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AppProcess private constructor(app: Application) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    /** Whether the phone has signal. */
    val connectivity = Connectivity(app)

    private val _model = MutableStateFlow<AppModel?>(null)
    /** The model, null until the data and the kept values have been read. */
    val model: StateFlow<AppModel?> = _model.asStateFlow()

    private var updater: DataUpdater? = null

    /** A reminder was tapped before the model had loaded. */
    private var gardenWaiting = false

    /** Whether a screen in this process has already decided whether the opening plays. It plays once a launch. */
    private var openingDecided = false

    /** True for the first screen made in this process, and false for every later one. */
    fun firstScreen(): Boolean {
        val first = !openingDecided
        openingDecided = true
        return first
    }

    /** The data check running now, or the last one, so a test can wait for it to finish. */
    @Volatile
    var dataCheck: Job? = null
        private set

    init {
        scope.launch {
            val online = { connectivity.isOnline.value }
            val settings = withContext(Dispatchers.IO) { ServiceSettings.forLaunch(app) }
            val loaded = withContext(Dispatchers.IO) { AppModel.load(app, settings, scope, online) }
            updater = DataUpdater(loaded, settings.site, settings.dataFolder, app.keptStore, online)
            _model.value = loaded
            if (gardenWaiting) {
                gardenWaiting = false
                loaded.openedFromNotification()
            }
            loaded.cameToFront()
            // At launch, and each time signal comes back.
            connectivity.isOnline.collect { if (it) refresh() }
        }
    }

    /** The app came to the front: take the time and the phone's answer again, send waiting events, check for data. */
    fun cameToFront() {
        val loaded = _model.value ?: return
        loaded.cameToFront()
        refresh()
    }

    /** A reminder was tapped: show This week on the Garden tab, now or as soon as the model has loaded. */
    fun openGarden() {
        val loaded = _model.value
        if (loaded == null) gardenWaiting = true else loaded.openedFromNotification()
    }

    /** Both do nothing without signal; the data check also does nothing until a day has passed since the last one. */
    private fun refresh() {
        val updater = updater ?: return
        if (dataCheck?.isActive != true) dataCheck = scope.launch { updater.checkIfDue() }
        _model.value?.events?.flush()
    }

    companion object {
        @Volatile
        private var held: AppProcess? = null

        /** This process's one AppProcess, made the first time it is asked for. */
        fun get(context: Context): AppProcess =
            held ?: synchronized(this) {
                held ?: AppProcess(context.applicationContext as Application).also { held = it }
            }

        /**
         * Forgets everything this process holds, as if the app had been closed and opened again. Off the main thread
         * it waits up to five seconds for the work to stop.
         */
        fun forget() {
            val forgotten = synchronized(this) { held.also { held = null } } ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                forgotten.job.cancel()
            } else {
                runBlocking { withTimeoutOrNull(5_000) { forgotten.job.cancelAndJoin() } }
            }
            forgotten.connectivity.close()
        }

        /** The model, if this process has loaded it. */
        fun loaded(): AppModel? = held?.model?.value
    }
}
