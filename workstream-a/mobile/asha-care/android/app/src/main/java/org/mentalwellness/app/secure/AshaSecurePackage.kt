package org.mentalwellness.app.secure

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/** Registers [AshaSecureModule] as a TurboModule. */
class AshaSecurePackage : BaseReactPackage() {

  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
      if (name == AshaSecureModule.NAME) AshaSecureModule(reactContext) else null

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(AshaSecureModule.NAME to ReactModuleInfo(AshaSecureModule.NAME, AshaSecureModule.NAME,
        canOverrideExistingModule = false, needsEagerInit = false, isCxxModule = false, isTurboModule = true))
  }
}
