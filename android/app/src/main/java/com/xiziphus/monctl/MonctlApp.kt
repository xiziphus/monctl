package com.xiziphus.monctl

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import com.xiziphus.monctl.net.MonctlClient

class MonctlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Walking out of Wi-Fi range (or back in) must not leave requests
        // pinned to the old route for the rest of the cache minute.
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = MonctlClient.forgetRoute()
                override fun onLost(network: Network) = MonctlClient.forgetRoute()
            }
        )
    }
}
