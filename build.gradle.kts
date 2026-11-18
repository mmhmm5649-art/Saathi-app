// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

val previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
  val message = throwable.message.orEmpty()
  val stack = throwable.stackTrace
  val isExactKspTeardownNpe =
    thread.name.startsWith("AWT-EventQueue") &&
      throwable is NullPointerException &&
      message.contains("ksp.com.intellij.openapi.application.Application.getService") &&
      message.contains("ksp.com.intellij.openapi.application.ApplicationManager.getApplication()\" is null") &&
      stack.any { it.className == "ksp.com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers" } &&
      stack.any {
        it.className == "ksp.com.intellij.openapi.fileEditor.FileDocumentManager" &&
          it.methodName == "getInstance"
      }
  if (!isExactKspTeardownNpe) {
    if (previousUncaughtHandler != null) {
      previousUncaughtHandler.uncaughtException(thread, throwable)
    } else {
      System.err.print("Exception in thread \"${thread.name}\" ")
      throwable.printStackTrace(System.err)
    }
  }
}

