// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.hints

import com.intellij.codeInsight.ExternalAnnotationsManager
import com.intellij.codeInsight.InferredAnnotationsManager
import com.intellij.codeInsight.MakeInferredAnnotationExplicit
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.MenuOnClickPresentation
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.codeInsight.hints.presentation.SequencePresentation
import com.intellij.codeInsight.javadoc.JavaDocInfoGenerator
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationBundle
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.ui.layout.*
import com.intellij.util.SmartList
import com.intellij.util.ui.JBUI
import java.awt.GridLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.reflect.KMutableProperty0

class AnnotationInlayProvider : InlayHintsProvider<AnnotationInlayProvider.Settings> {
  override fun getCollectorFor(file: PsiFile,
                               editor: Editor,
                               settings: Settings,
                               sink: InlayHintsSink): InlayHintsCollector? {
    val project = file.project
    val document = PsiDocumentManager.getInstance(project).getDocument(file)
    return object : FactoryInlayHintsCollector(editor) {
      override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        if (file.project.service<DumbService>().isDumb) return true
        val presentations = SmartList<InlayPresentation>()
        if (element is PsiModifierListOwner) {
          var annotations = emptySequence<PsiAnnotation>()
          if (settings.showExternal) {
            annotations += ExternalAnnotationsManager.getInstance(project).findExternalAnnotations(element).orEmpty()
          }
          if (settings.showInferred) {
            annotations += InferredAnnotationsManager.getInstance(project).findInferredAnnotations(element)
          }

          val shownAnnotations = mutableSetOf<String>()
          annotations.forEach {
            val nameReferenceElement = it.nameReferenceElement
            if (nameReferenceElement != null && element.modifierList != null &&
                (shownAnnotations.add(nameReferenceElement.qualifiedName) || JavaDocInfoGenerator.isRepeatableAnnotationType(it))) {
              presentations.add(createPresentation(it, element))
            }
          }
          val modifierList = element.modifierList
          if (modifierList != null) {
            val offset = modifierList.textRange.startOffset
            if (presentations.isNotEmpty()) {
              val presentation = SequencePresentation(presentations)
              val prevSibling = element.prevSibling
              when {
                // element is first in line
                prevSibling is PsiWhiteSpace && prevSibling.textContains('\n') && document != null -> {
                  val width = EditorUtil.getPlainSpaceWidth(editor)
                  val line = document.getLineNumber(offset)
                  val startOffset = document.getLineStartOffset(line)
                  val column = offset - startOffset
                  val shifted = factory.inset(presentation, left = column * width)

                  sink.addBlockElement(offset, false, true, 0, shifted)
                }
                else -> {
                  sink.addInlineElement(offset, false, factory.inset(presentation, left = 1, right = 1))
                }
              }
            }
          }
        }
        return true
      }

      private fun createPresentation(
        annotation: PsiAnnotation,
        element: PsiModifierListOwner
      ): MenuOnClickPresentation {
        val presentation = annotationPresentation(annotation)
        return MenuOnClickPresentation(presentation, project) {
          val makeExplicit = InsertAnnotationAction(project, file, element)
          listOf(
            makeExplicit,
            ToggleSettingsAction("Turn off external annotations", settings::showExternal, settings),
            ToggleSettingsAction("Turn off inferred annotations", settings::showInferred, settings)
          )
        }
      }

      private fun annotationPresentation(annotation: PsiAnnotation): InlayPresentation = with(factory) {
        val nameReferenceElement = annotation.nameReferenceElement
        val parameterList = annotation.parameterList
        roundWithBackground(seq(
          smallText("@"),
          psiSingleReference(smallText(nameReferenceElement?.referenceName ?: "")) { nameReferenceElement?.resolve() },
          parametersPresentation(parameterList)
        ))
      }

      private fun parametersPresentation(parameterList: PsiAnnotationParameterList) = with(factory) {
        val attributes = parameterList.attributes
        when {
          attributes.isEmpty() -> smallText("()")
          else -> insideParametersPresentation(attributes, collapsed = parameterList.textLength > 60)
        }
      }

      private fun insideParametersPresentation(attributes: Array<PsiNameValuePair>, collapsed: Boolean) = with(factory) {
        collapsible(
          smallText("("),
          smallText("..."),
          {
            join(
              presentations = attributes.map { pairPresentation(it) },
              separator = { smallText(", ") }
            )
          },
          smallText(")"),
          collapsed
        )
      }

      private fun pairPresentation(attribute: PsiNameValuePair) = with(factory) {
        when (val attrName = attribute.name) {
          null -> attrValuePresentation(attribute)
          else -> seq(
            psiSingleReference(smallText(attrName), resolve = { attribute.reference?.resolve() }),
            smallText(" = "),
            attrValuePresentation(attribute)
          )
        }
      }

      private fun PresentationFactory.attrValuePresentation(attribute: PsiNameValuePair) =
        smallText(attribute.value?.text ?: "")
    }
  }

  override fun createSettings(): Settings = Settings()

  override val name: String
    get() = "Annotations"
  override val key: SettingsKey<Settings>
    get() = ourKey
  override val previewText: String?
    get() = """
      class Demo {
        private static int pure(int x, int y) {
          return x * y + 10;
        }
      }
    """.trimIndent()

  override fun createConfigurable(settings: Settings): ImmediateConfigurable {
    return object : ImmediateConfigurable {
      val showExternalCheckBox = JCheckBox(ApplicationBundle.message("editor.appearance.show.external.annotations"))
      val showInferredCheckBox = JCheckBox(ApplicationBundle.message("editor.appearance.show.inferred.annotations"))
      override fun createComponent(listener: ChangeListener): JComponent {
        reset()
        fun onUiChanged() {
          settings.showInferred = showInferredCheckBox.isSelected
          settings.showExternal = showExternalCheckBox.isSelected
          listener.settingsChanged()
          if (!settings.showExternal && !settings.showInferred) {
            listener.didDeactivated()
          }
        }
        val panel = JPanel(GridLayout(1, 1))
        panel.add(panel {
          row {
            showExternalCheckBox.addChangeListener { onUiChanged() }
            showExternalCheckBox()
          }
          row {
            showInferredCheckBox.addChangeListener { onUiChanged() }
            showInferredCheckBox()
          }
        })
        panel.border = JBUI.Borders.empty(0, 20, 0, 0)
        return panel
      }

      override fun reset() {
        showExternalCheckBox.isSelected = settings.showExternal
        showInferredCheckBox.isSelected = settings.showInferred
      }

      override val mainCheckboxText: String
        get() = "Show hints for:"
    }
  }

  companion object {
    val ourKey: SettingsKey<Settings> = SettingsKey("annotation.hints")
  }

  data class Settings(var showInferred: Boolean = false, var showExternal: Boolean = true)


  class ToggleSettingsAction(val text: String, val prop: KMutableProperty0<Boolean>, val settings: Settings) : AnAction() {

    override fun update(e: AnActionEvent) {
      val presentation = e.presentation
      presentation.text = text
    }

    override fun actionPerformed(e: AnActionEvent) {
      prop.set(!prop.get())
      val storage = ServiceManager.getService(InlayHintsSettings::class.java)
      storage.storeSettings(ourKey, JavaLanguage.INSTANCE, settings)
      InlayHintsPassFactory.forceHintsUpdateOnNextPass()
    }

  }
}

class InsertAnnotationAction(
  private val project: Project,
  private val file: PsiFile,
  private val element: PsiModifierListOwner
) : AnAction() {
  override fun update(e: AnActionEvent) {
    e.presentation.text = "Insert annotation"
  }

  override fun actionPerformed(e: AnActionEvent) {
    val intention = MakeInferredAnnotationExplicit()
    if (intention.isAvailable(project, file, element)) {
      intention.makeAnnotationsExplicit(project, file, element)
    }
  }
}