package com.meshwalk.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class MeshWalkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.MESH_DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("MeshWalk application initialized")
    }
}
