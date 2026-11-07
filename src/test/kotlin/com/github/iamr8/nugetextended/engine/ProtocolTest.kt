package com.github.iamr8.nugetextended.engine

import com.github.iamr8.nugetextended.cli.OutdatedOptions
import com.github.iamr8.nugetextended.cli.PreRelease
import com.github.iamr8.nugetextended.cli.VersionLock
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val gson = Gson()

    @Test
    fun parsesScanResultWithMissingFieldsAsDefaults() {
        val json = """
            {"projects":[{"path":"/r/A/A.csproj","name":"A","frameworks":[{"framework":"net8.0","packages":[
              {"id":"Polly","requested":"[7.0.0,8.0.0)","resolved":"7.0.0","target":"7.2.4","severity":"Minor","capped":"8.8.0"}
            ]}]}],"stale":["/r/B/B.csproj"]}
        """
        val r = gson.fromJson(json, ScanResult::class.java)

        val row = r.projects.single().frameworks.single().packages.single()
        assertEquals("7.2.4", row.target)
        assertEquals("8.8.0", row.capped)
        assertEquals(false, row.restoreOnly)
        assertNull(row.reason)
        assertEquals(false, row.central)
        assertEquals(listOf("/r/B/B.csproj"), r.stale)
        assertTrue(r.failures.isEmpty())
        assertTrue(r.sourceFailures.isEmpty())
    }

    // Break: PackageRow has no `central` field, or Gson maps it to another name.
    @Test
    fun parsesCentralFlag() {
        val row = gson.fromJson("""{"id":"Serilog","central":true}""", PackageRow::class.java)
        assertEquals(true, row.central)
    }

    @Test
    fun parsesUpgradePlan() {
        val json = """
            {"edits":[{"op":"set","file":"/r/Directory.Packages.props","target":"attribute","itemType":"PackageVersion",
              "identityAttr":"Include","identity":"Polly","condition":null,"groupCondition":null,"name":"Version",
              "expected":"[7.0.0,8.0.0)","value":"[7.2.4,8.0.0)","line":4,"column":5}],
             "restoreProjects":["/r/A/A.csproj"],"alsoChanges":[{"project":"/r/B/B.csproj","id":"Polly"}],
             "skipped":[{"project":"/r/C/C.csproj","id":"X","reason":"floating version - restore picks the newest match"}]}
        """
        val plan = gson.fromJson(json, UpgradePlan::class.java)

        assertEquals("[7.2.4,8.0.0)", plan.edits.single().value)
        assertEquals(4, plan.edits.single().line)
        assertEquals("Polly", plan.alsoChanges.single().id)
        assertEquals("X", plan.skipped.single().id)
    }

    @Test
    fun optionsMapToEngineNames() {
        val o = OutdatedOptions(preRelease = PreRelease.Always, versionLock = VersionLock.Minor, maximumVersion = "9.0", olderThanDays = 7)
        val json = JsonParser.parseString(gson.toJson(EngineOptions.from(o, checkUpdates = false))).asJsonObject

        assertEquals("Always", json.get("preRelease").asString)
        assertEquals("Minor", json.get("versionLock").asString)
        assertEquals("9.0", json.get("maximumVersion").asString)
        assertEquals(7, json.get("olderThanDays").asInt)
        assertEquals(false, json.get("checkUpdates").asBoolean)
        assertEquals("Warning", json.get("credLogLevel").asString)
    }
}
