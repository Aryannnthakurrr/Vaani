package com.saathi.app

import com.saathi.app.features.care.PatientReply
import org.junit.Assert.assertEquals
import org.junit.Test

class PatientReplyTest {
    @Test
    fun nameAndAgeInOneAnswer() {
        assertEquals(PatientReply.Parsed("Kamla Devi", 72), PatientReply.parse("Kamla Devi, 72"))
        assertEquals(PatientReply.Parsed("Ramesh", 68), PatientReply.parse("mera naam Ramesh hai aur umar 68 saal"))
        assertEquals(PatientReply.Parsed("सुनीता", 70), PatientReply.parse("सुनीता, 70 साल"))
        assertEquals(PatientReply.Parsed("Arjun Mehta", 45), PatientReply.parse("The patient is Arjun Mehta, 45 years old"))
    }

    @Test
    fun aMissingPartIsLeftForTheFollowUp() {
        assertEquals(PatientReply.Parsed("Kamla", null), PatientReply.parse("Kamla"))
        assertEquals(PatientReply.Parsed(null, 72), PatientReply.parse("72 saal"))
        assertEquals(PatientReply.Parsed(null, null), PatientReply.parse(null))
    }
}
