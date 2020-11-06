package com.ryanheise.just_audio

import androidx.annotation.NonNull

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodChannel

/** JustAudioPlugin */
class JustAudioPlugin: FlutterPlugin{
  private lateinit var channel : MethodChannel
  private lateinit var methodCallHandler: MainMethodCallHandler


  /** v2 plugin registration */
  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    methodCallHandler = MainMethodCallHandler(flutterPluginBinding.applicationContext, flutterPluginBinding.binaryMessenger)
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "com.ryanheise.just_audio.methods")
    channel.setMethodCallHandler(methodCallHandler)
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    methodCallHandler.dispose()
    channel.setMethodCallHandler(null)
  }
}
