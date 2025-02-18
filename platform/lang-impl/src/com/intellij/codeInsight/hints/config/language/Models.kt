// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.hints.config.language

import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.config.ParameterHintsSettingsPanel
import com.intellij.configurationStore.deserializeInto
import com.intellij.configurationStore.serialize
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import javax.swing.JComponent


abstract class InlayProviderSettingsModel(var isEnabled: Boolean, val id: String) {
  var onChangeListener: ChangeListener? = null

  abstract val name: String

  abstract val mainCheckBoxLabel: String

  abstract val component: JComponent

  // Invariant: if previewText == null, this method is not invoked
  abstract fun collectAndApply(editor: Editor, file: PsiFile)

  abstract val previewText: String?

  abstract fun apply()

  abstract fun isModified(): Boolean

  abstract fun reset()
}

class ParameterInlayProviderSettingsModel(
  val provider: InlayParameterHintsProvider,
  val language: Language
) : InlayProviderSettingsModel(isParameterHintsEnabledForLanguage(language), "parameter.hints.old") {
  override val mainCheckBoxLabel: String
    get() = provider.mainCheckboxText

  override val name: String
    get() = "Parameter hints"
  override val previewText: String?
    get() = null
  override val component by lazy {
    val listener: () -> Unit = { onChangeListener?.didDeactivated() }
    ParameterHintsSettingsPanel(
      language = language,
      options = provider.supportedOptions,
      blackListSupported = provider.isBlackListSupported,
      onDeactivated = if (provider.isExhaustive) listener else null
    )
  }

  override fun collectAndApply(editor: Editor, file: PsiFile) = throw UnsupportedOperationException()


  override fun toString(): String = name

  override fun apply() {
    component.saveOptions()
    setShowParameterHintsForLanguage(isEnabled, language)
  }

  override fun isModified(): Boolean {
    if (isEnabled != isParameterHintsEnabledForLanguage(language)) return true
    return component.isModified()
  }

  override fun reset() {
    isEnabled = isParameterHintsEnabledForLanguage(language)
    component.reset()
  }
}

class NewInlayProviderSettingsModel<T : Any>(
  private val providerWithSettings: ProviderWithSettings<T>,
  private val config: InlayHintsSettings
) : InlayProviderSettingsModel(
  isEnabled = config.hintsEnabled(providerWithSettings.provider.key, providerWithSettings.language),
  id = providerWithSettings.provider.key.id
) {
  override val name: String
    get() = providerWithSettings.provider.name

  override val mainCheckBoxLabel: String
    get() = providerWithSettings.configurable.mainCheckboxText
  override val component by lazy {
    providerWithSettings.configurable.createComponent(onChangeListener!!)
  }

  override fun collectAndApply(editor: Editor, file: PsiFile) {
    providerWithSettings.getCollectorWrapperFor(file, editor, providerWithSettings.language)?.collectTraversingAndApply(editor, file)
  }

  override val previewText: String?
    get() = providerWithSettings.provider.previewText


  override fun apply() {
    config.storeSettings(providerWithSettings.provider.key, providerWithSettings.language, providerWithSettings.settings)
    config.changeHintTypeStatus(providerWithSettings.provider.key, providerWithSettings.language, isEnabled)
  }

  override fun isModified(): Boolean {
    if (isEnabled != config.hintsEnabled(providerWithSettings.provider.key, providerWithSettings.language)) return true
    val inSettings = providerWithSettings.settings
    val stored = providerWithSettings.provider.getActualSettings(config, providerWithSettings.language)
    return inSettings != stored
  }

  override fun toString(): String = name

  override fun reset() {
    // Workaround for deep copy
    val obj = providerWithSettings.provider.getActualSettings(config, providerWithSettings.language)
    serialize(obj)?.deserializeInto(providerWithSettings.settings)
    providerWithSettings.configurable.reset()
    isEnabled = config.hintsEnabled(providerWithSettings.provider.key, providerWithSettings.language)
  }
}