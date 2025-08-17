package br.com.popgas.flutter_sms_listener

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.Keep
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar

/** FlutterSmsListenerPlugin */
class FlutterSmsListenerPlugin : FlutterPlugin, MethodCallHandler {

  private var context: Context? = null
  private var channel: MethodChannel? = null

  private var receiver: SMSBroadcastListener? = null
  private var pendingResult: Result? = null

  // ===== FlutterPlugin lifecycle =====
  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    context = binding.applicationContext
    channel = MethodChannel(binding.binaryMessenger, "flutter_sms_listener").also {
      it.setMethodCallHandler(this)
    }
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    // Clean up any active receiver to avoid leaks
    context?.let { unregister(it) }
    channel?.setMethodCallHandler(null)
    channel = null
    context = null
  }

  // ===== Backward-compat (old v1 embedding) =====
  @Keep
  companion object {
    @JvmStatic
    fun registerWith(registrar: Registrar) {
      val plugin = FlutterSmsListenerPlugin().apply {
        context = registrar.context()
        channel = MethodChannel(registrar.messenger(), "flutter_sms_listener").also {
          it.setMethodCallHandler(this)
        }
      }
    }

    private const val TAG = "FlutterSmsListener"
  }

  // ===== Channel methods =====
  override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
      "getAppSignature" -> {
        val ctx = context ?: return result.error("NO_CONTEXT", "Context not attached", null)
        val signatures = AppSignatureHelper(ctx).getAppSignatures()
        if (signatures.isNotEmpty()) {
          result.success(signatures[0])
        } else {
          result.error("NO_SIGNATURE", "No app signatures found", null)
        }
      }

      "startListening" -> {
        val ctx = context ?: return result.error("NO_CONTEXT", "Context not attached", null)
        // Avoid multiple receivers at once
        unregister(ctx)
        receiver = SMSBroadcastListener().also { rcv ->
          val filter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
          if (Build.VERSION.SDK_INT >= 33) {
            // From Android 13+, must declare exposure for external broadcasts
            ctx.registerReceiver(rcv, filter, Context.RECEIVER_EXPORTED)
          } else {
            @Suppress("DEPRECATION")
            ctx.registerReceiver(rcv, filter)
          }
        }
        pendingResult = result
        startRetriever(ctx)
      }

      "stopListening" -> {
        val ctx = context ?: return result.error("NO_CONTEXT", "Context not attached", null)
        unregister(ctx)
        result.success(null)
      }

      else -> result.notImplemented()
    }
  }

  // ===== Internal helpers =====
  private fun startRetriever(ctx: Context) {
    val client = SmsRetriever.getClient(ctx)
    val task = client.startSmsRetriever()

    task.addOnSuccessListener {
      Log.d(TAG, "SmsRetriever task started")
      // Do not complete the result yet; we’ll complete when SMS arrives
    }

    task.addOnFailureListener { e ->
      Log.e(TAG, "SmsRetriever start failed", e)
      pendingResult?.error("START_FAILED", "Failed to start SMS retriever", e?.message)
      pendingResult = null
      unregister(ctx)
    }
  }

  private fun unregister(ctx: Context) {
    receiver?.let {
      try {
        ctx.unregisterReceiver(it)
      } catch (_: IllegalArgumentException) {
        // Already unregistered; ignore
      }
    }
    receiver = null
    // Do not null out pendingResult here; caller may call stopListening() intentionally.
    pendingResult = null
  }

  // ===== Broadcast Receiver =====
  inner class SMSBroadcastListener : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (SmsRetriever.SMS_RETRIEVED_ACTION != intent.action) return

      val extras = intent.extras ?: return
      val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return

      when (status.statusCode) {
        CommonStatusCodes.SUCCESS -> {
          val message = extras.get(SmsRetriever.EXTRA_SMS_MESSAGE) as? String
          Log.d(TAG, "SMS retrieved")
          pendingResult?.success(message)
          pendingResult = null
          unregister(context)
        }
        CommonStatusCodes.TIMEOUT -> {
          Log.w(TAG, "SMS retriever timed out")
          pendingResult?.error("TIMEOUT", "SMS retriever timed out", null)
          pendingResult = null
          unregister(context)
        }
        else -> {
          Log.w(TAG, "Unhandled status: ${status.statusCode}")
          pendingResult?.error("UNKNOWN_STATUS", "Unhandled status: ${status.statusCode}", null)
          pendingResult = null
          unregister(context)
        }
      }
    }
  }
}
