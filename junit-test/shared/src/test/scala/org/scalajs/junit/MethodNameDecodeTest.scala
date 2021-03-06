package org.scalajs.junit

import org.junit.Test
import org.scalajs.junit.utils.JUnitTest

class MethodNameDecodeTest {
  @Test def `abcd ∆ƒ \uD83D\uDE00 * #&$`(): Unit = ()
}

class MethodNameDecodeTestAssertions extends JUnitTest {
  override protected val frameworkArgss: List[List[String]] = List(
    List("-v"),
    List("-v", "-n"),
    List("-v", "-s"),
    List("-v", "-s", "-n")
  )

  protected def expectedOutput(builder: OutputBuilder): OutputBuilder = {
    val methodName =
      if (builder.argInfo.decodeScalaNames) "abcd ∆ƒ \uD83D\uDE00 * #&$"
      else "abcd$u0020$u2206ƒ$u0020$uD83D$uDE00$u0020$times$u0020$hash$amp$"
    builder.success(methodName)
  }
}
