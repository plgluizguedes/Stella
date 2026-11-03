package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testExamDocumentUpdate_preservesIdAndAttachment() {
    val original = com.example.data.model.ExamDocument(
      id = 123L,
      name = "Hemograma",
      fileType = "PDF",
      dateString = "20/09/2026",
      clinicName = "VetCare",
      vetName = "Dra. Ana",
      category = "Exame de sangue",
      notes = "Coletado em jejum",
      fileUriOrPath = "content://com.android.providers.media.documents/document/1234",
      createdAt = 1700000000000L
    )

    // Simulate editing textual fields while preserving ID and attachment
    val updated = original.copy(
      name = "Hemograma Completo",
      category = "Exame de sangue",
      notes = "Valores normais, sem anemia",
      fileUriOrPath = original.fileUriOrPath
    )

    // ID must be strictly preserved
    assertEquals(123L, updated.id)
    assertEquals(original.id, updated.id)

    // Attachment path/URI must be strictly preserved
    assertEquals("content://com.android.providers.media.documents/document/1234", updated.fileUriOrPath)
    assertEquals(original.fileUriOrPath, updated.fileUriOrPath)

    // CreatedAt must be preserved
    assertEquals(1700000000000L, updated.createdAt)

    // Textual fields must be updated
    assertEquals("Hemograma Completo", updated.name)
    assertEquals("Valores normais, sem anemia", updated.notes)
  }
}
