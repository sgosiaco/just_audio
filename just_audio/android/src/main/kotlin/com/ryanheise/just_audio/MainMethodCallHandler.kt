package com.ryanheise.just_audio

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainMethodCallHandler(val applicationContext: Context, val binaryMessenger: BinaryMessenger): MethodChannel.MethodCallHandler {
    private val players = HashMap<String, AudioPlayer>()

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val request : Map<Any, Any> = call.arguments()
        val id : String = request["id"] as String
        when (call.method) {
            "init" -> {
                players[id] = AudioPlayer(applicationContext, binaryMessenger, id)
                result.success(null)
            }
            "disposePlayer" -> {
                val player : AudioPlayer? = players[id]
                player?.dispose()
                players.remove(id)
                result.success(HashMap<String, Any>()) //result.success(new HashMap<String, Object>());
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    fun dispose() {
        players.forEach { it.value.dispose() }
    }
}