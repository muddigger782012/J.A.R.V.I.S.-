package com.assistant.core.engine

import android.content.Context
import com.assistant.core.models.CapabilityState

class CapabilityDetector {

    fun detect(context: Context): CapabilityState = PrivilegedAccess.read(context).capabilities()
}
