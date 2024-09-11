package eu.kanade.tachiyomi.network

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import net.freehaven.tor.control.TorControlCommands
import net.freehaven.tor.control.TorControlConnection
import okio.IOException
import org.torproject.jni.TorService
import org.torproject.jni.TorService.LocalBinder

enum class TorProxyStatus {
    ON, OFF, STARTING, STOPPING
}

class TorNetworkService(private val context: Context) {
    private val receiver = TorServiceReceiver()
    private var conn: TorControlConnection? = null

    private var proxyStatus = TorProxyStatus.OFF

    fun init() {
        receiver.register()

        context.bindService(
            Intent(context, TorService::class.java),
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val torService = (service as LocalBinder).service

                    while (torService.torControlConnection.also { conn = it } == null) {
                        try {
                            Thread.sleep(500)
                        } catch (e: InterruptedException) {
                            e.printStackTrace()
                        }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                }
            },
            Context.BIND_AUTO_CREATE,
        )
    }

    fun isConnected(): Boolean {
        return proxyStatus == TorProxyStatus.ON
    }

    private inner class TorServiceReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(TorService.EXTRA_STATUS)

            when (status) {
                "ON" -> proxyStatus = TorProxyStatus.ON
                "OFF" -> proxyStatus = TorProxyStatus.OFF
                "STARTING" -> proxyStatus = TorProxyStatus.STARTING
                "STOPPING" -> proxyStatus = TorProxyStatus.STOPPING
            }
        }

        fun register() {
            ContextCompat.registerReceiver(
                context,
                this,
                IntentFilter(TorService.ACTION_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }
}
