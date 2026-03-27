package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import android.view.KeyEvent
import android.view.MenuItem
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPlay
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import moe.matsuri.nb4a.utils.Util
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import libcore.Libcore
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    NavigationView.OnNavigationItemSelectedListener {

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        if (themeResId !in intArrayOf(
                R.style.Theme_SagerNet_Black
            )
        ) {
            navigation = binding.navView
            binding.drawerLayout.removeView(binding.navViewBlack)
        } else {
            navigation = binding.navViewBlack
            binding.drawerLayout.removeView(binding.navView)
        }
        navigation.setNavigationItemSelectedListener(this)

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        }
        onBackPressedDispatcher.addCallback {
            if (supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment) {
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_configuration)
            }
        }

        binding.fab.setOnClickListener {
            if (DataStore.serviceState.canStop)
                SagerNet.stopService()
            else {
                runOnDefaultDispatcher {
                    try {
                        simulateVolumeDown()
                        delay(1000)
                        simulateVolumeDown()
                        delay(2000)
                    } catch (e: Exception) {
                        Log.e("VolumeKey", "Failed to simulate volume up: ${e.message}")
                    }

                    try {
                        forceStopApp("com.netmarble.rfnext")
                        delay(2000)
                    } catch (e: Exception) {
                        Log.e("ForceStopApp", "Failed to forceStopApp: ${e.message}")
                    }

                    val result = readXmlFile("/data/data/com.netmarble.thered/shared_prefs/cpp_native_shared.xml")
                        .recoverCatching { 
                            readXmlFile("/data/data/com.netmarble.rfnext/shared_prefs/cpp_native_shared.xml").getOrThrow() 
                        }
                    when {
                        result.isSuccess -> {
                            Log.d("RestrictionSMS", "result.isSuccess")
                            val client = Libcore.newHttpClient()
                            try {
                                val xmlContent = result.getOrNull() ?: ""
                                val xmlMap = parseCppNativeSharedXmlToMap(xmlContent)
                                val jsonObject = convertMapToJson(xmlMap)
                                val response = client.newRequest().apply {
                                    setURL("http://netmarble.mammon.icu:8899/api/v1/restriction/release/sms/create")
                                    setMethod("POST")
                                    setHeader("Content-Type", "application/json")
                                    setContentString(jsonObject.toString(2))
                                }.execute()
                                onMainDispatcher {
                                    snackbar("Restriction SMS success").show()
                                }
                            } catch (e: Exception) {
                                onMainDispatcher {
                                    snackbar("Restriction SMS failed: ${e.message}").show()
                                }
                            } finally {
                                client.close()
                            }
                        }
                        result.isFailure -> {
                            Log.d("RestrictionSMS", "result.isFailure")
                            onMainDispatcher {
                                snackbar("read xml file failed: ${result.exceptionOrNull()?.message}").show()
                            }
                        }
                    }
                    delay(1000)
                    try {
                        simulateVolumeUp()
                        delay(2000)
                        simulateVolumeUp()
                        delay(2000)
//                        Runtime.getRuntime().exec(arrayOf("su", "-c", "sendevent /dev/input/event3 1 115 1 && sendevent /dev/input/event3 0 0 0 && sleep 0.01 && sendevent /dev/input/event3 1 115 0 && sendevent /dev/input/event3 0 0 0"))
                    } catch (e: Exception) {
                        Log.e("VolumeKey", "Failed to simulate volume up: ${e.message}")
                    }
                    onMainDispatcher {

                    }
                }
            }

        }
        binding.stats.setOnClickListener { if (DataStore.serviceState.connected) binding.stats.testConnection() }

        setContentView(binding.root)
        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        refreshNavMenu(DataStore.enableClashAPI)

        // sdk 33 notification
        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission =
                ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                //动态申请
                ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(POST_NOTIFICATIONS), 0
                )
            }
        }
    }

    fun refreshNavMenu(clashApi: Boolean) {
        if (::navigation.isInitialized) {
            navigation.menu.findItem(R.id.nav_traffic)?.isVisible = clashApi
            navigation.menu.findItem(R.id.nav_tuiguang)?.isVisible = !isPlay
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        group.name = group.name.takeIf { !it.isNullOrBlank() }
            ?: ("Subscription #" + System.currentTimeMillis())

        onMainDispatcher {

            displayFragmentWithId(R.id.nav_group)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        // official exe

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, pluginEntity.displayName
                )
            )
            .setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_learn_more) { _, _ ->
                launchCustomTab("https://matsuridayo.github.io/m-plugin/")
            }
            .show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }
            .show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) binding.drawerLayout.closeDrawers() else {
            return displayFragmentWithId(item.itemId)
        }
        return true
    }


    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        if (fragment is ConfigurationFragment) {
            binding.stats.allowShow = true
            binding.fab.show()
        } else if (!DataStore.showBottomBar) {
            binding.stats.allowShow = false
            binding.stats.performHide()
            binding.fab.hide()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }

            R.id.nav_group -> displayFragment(GroupFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_settings -> displayFragment(SettingsFragment())
            R.id.nav_traffic -> displayFragment(WebviewFragment())
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_logcat -> displayFragment(LogcatFragment())
            R.id.nav_faq -> {
                launchCustomTab("https://matsuridayo.github.io/")
                return false
            }

            R.id.nav_about -> displayFragment(AboutFragment())
            R.id.nav_tuiguang -> {
                launchCustomTab("https://neko-box.pages.dev/喵")
                return false
            }

            else -> return false
        }
        navigation.menu.findItem(id).isChecked = true
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        DataStore.serviceState = state

        binding.fab.changeState(state, DataStore.serviceState, animate)
        binding.stats.changeState(state)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (binding.fab.isShown) {
                anchorView = binding.fab
            }
            // TODO
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.stats.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
    }

    override fun cbTrafficUpdate(data: TrafficData) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(data)
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (super.onKeyDown(keyCode, event)) return true
                binding.drawerLayout.open()
                navigation.requestFocus()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }


    private suspend fun readFileContent(filePath: String): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val fileLines = mutableListOf<String>()

            // 使用 Runtime 执行带 --mount-master 参数的 su 命令
//            val process = Runtime.getRuntime().exec(arrayOf("su", "--mount-master", "-c", "cat $filePath"))
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $filePath"))
            val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))

            var currentLine: String?
            while (bufferedReader.readLine().also { currentLine = it } != null) {
                fileLines.add(currentLine.toString())
            }

            val exitCode = process.waitFor()
            bufferedReader.close()

            // 检查进程执行结果
            if (exitCode != 0) {
                return@withContext Result.failure<String>(RuntimeException("Process exited with code: $exitCode"))
            }

            Result.success(fileLines.joinToString("\n"))
        } catch (securityException: SecurityException) {
            Result.failure<String>(securityException)
        } catch (ioException: java.io.IOException) {
            Result.failure<String>(ioException)
        } catch (interruptedException: InterruptedException) {
            Result.failure<String>(interruptedException)
        } catch (exception: Exception) {
            Result.failure<String>(exception)
        }
    }

    // 专门读取XML文件的包装函数
    private suspend fun readXmlFile(filePath: String): Result<String> {
        return readFileContent(filePath)
    }

    private fun parseCppNativeSharedXmlToMap(xmlString: String): Map<String, String> {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xmlString))

        val result = mutableMapOf<String, String>()
        var eventType = parser.eventType
        var currentName: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "string") {
                        currentName = parser.getAttributeValue(null, "name")
                    }
                }
                XmlPullParser.TEXT -> {
                    if (currentName != null) {
                        result[currentName] = parser.text
                        currentName = null
                    }
                }
            }
            eventType = parser.next()
        }

        return result
    }

    private fun convertMapToJson(xmlMap: Map<String, String>): JSONObject {
        return JSONObject(xmlMap)
    }

    private fun getEventDevices(): List<String> {
        return runCatching {
            // 使用 Kotlin 的 runCatching 简化异常处理
            val command = arrayOf("su", "-c", "ls /dev/input/ 2>/dev/null || echo ''")
            val process = Runtime.getRuntime().exec(command)

            // 读取输出并处理
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                Log.w("VolumeKey", "Command exited with code: $exitCode")
                return emptyList()
            }

            // 解析输出，过滤空行和非 event 设备
            output.lineSequence()
                .map { it.trim() }
                .filter {
                    it.isNotEmpty() &&
                            it.startsWith("event") &&
                            it.length > "event".length &&
                            it.substringAfter("event").all { c -> c.isDigit() }
                }
                .toList()
        }.getOrElse {
            Log.e("VolumeKey", "Failed to get devices: ${it.message}")
            emptyList()
        }
    }

    private fun checkDeviceHasVolumeKey(devicePath: String): Boolean {
        return try {
            // 直接使用 grep 命令检查
            val command = arrayOf(
                "su", "-c",
                "getevent -p $devicePath 2>&1 | grep -E '0072|0073'"
            )

            val process = Runtime.getRuntime().exec(command)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            // 如果 grep 找到匹配项，退出码为 0
            val found = exitCode == 0 && output.isNotEmpty()

            Log.d("VolumeKey", "Volume key check for $devicePath: $found (output length: ${output.length})")

            if (found && output.isNotBlank()) {
                Log.d("VolumeKey", "Found output: ${output.trim()}")
            }

            found
        } catch (e: Exception) {
            Log.e("VolumeKey", "Error checking device $devicePath: ${e.message}")
            false
        }
    }

    private fun findVolumeKeyDevice(): String? {
        return try {
            // 1. 获取所有event设备
            val devices = getEventDevices()
            Log.d("VolumeKey", "Available devices: $devices")

            if (devices.isEmpty()) {
                Log.e("VolumeKey", "No event devices found!")
                return null
            }

            // 2. 查找包含音量键的设备
            for (device in devices) {
                val devicePath = "/dev/input/$device"
                if (checkDeviceHasVolumeKey(devicePath)) {
                    Log.d("VolumeKey", "Found volume key device: $devicePath")
                    return devicePath
                }
            }
            return null
        } catch (e: Exception) {
            Log.e("VolumeKey", "Error finding volume device: ${e.message}")
            null
        }
    }


    private fun simulateVolumeDown(): Boolean {
        val device = findVolumeKeyDevice()

        if (device == null) {
            Log.e("VolumeKey", "No volume key device found!")
            return false
        }

        return simulateKey(device, 114)
    }

    private fun simulateVolumeUp(): Boolean {
        val device = findVolumeKeyDevice()

        if (device == null) {
            Log.e("VolumeKey", "No volume key device found!")
            return false
        }

//        return simulateKey(device, 115)
        return simulateKey(device, 115)
    }

    private fun simulateKey(devicePath: String, keyCode: Int): Boolean {
        Log.d("VolumeKey", "Simulating key $keyCode on $devicePath")

        return try {
            // 创建完整的命令
            val command = """
                sendevent $devicePath 1 $keyCode 1 && 
                sendevent $devicePath 0 0 0 && 
                sleep 0.01 && 
                sendevent $devicePath 1 $keyCode 0 && 
                sendevent $devicePath 0 0 0
            """.trimIndent().replace("\n", " ")

            // 执行命令
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                Log.d("VolumeKey", "Key simulation successful")
                true
            } else {
                // 读取错误信息
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                val error = StringBuilder()
                var line: String?
                while (errorReader.readLine().also { line = it } != null) {
                    error.append(line).append("\n")
                }
                errorReader.close()
                Log.e("VolumeKey", "Command failed: $error")
                false
            }
        } catch (e: Exception) {
            Log.e("VolumeKey", "Failed to simulate key: ${e.message}")
            false
        }
    }

    private fun forceStopApp(packageName: String): Boolean {
        return try {
            val command = arrayOf(
                "su", "-c",
                "am force-stop $packageName"
            )

            val process = Runtime.getRuntime().exec(command)
            process.waitFor()
            true
        } catch (e: Exception) {
            Log.e("forceStopApp", "Failed forceStopApp: ${e.message}")
            false
        }
    }

}
