package com.landosol.toolbox.labyrinth

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * 策略设置「特别装备」的设置侧行为，契约见 `android/docs/special-equipment.md`。
 *
 * 覆盖默认关闭、空集合校验、三类关卡可任意组合、旧 JSON 继续解码、界面与执行读同一份字段。
 */
class LabyrinthSpecialEquipmentSettingsTest {
    private val allKinds = setOf(LabyrinthCombatKind.NORMAL, LabyrinthCombatKind.EX, LabyrinthCombatKind.BOSS)

    @Test fun `special equipment defaults to off and lists every stage kind`() {
        val defaults = LabyrinthStrategySettings()
        assertEquals(LabyrinthSpecialEquipmentMode.OFF, defaults.specialEquipmentMode)
        assertEquals(allKinds, defaults.specialEquipmentKinds)
        assertNull(defaults.validationError())
        // 默认配置没有任何改动，保存按钮仍然不可用。
        assertFalse(defaults.canSaveDraft(defaults, saving = false))
    }

    @Test fun `legacy payloads without the new fields decode and stay off`() {
        val legacy = LabyrinthStrategySettingsCodec.decode("{\"playerWeight\":50}")
        assertEquals(LabyrinthSpecialEquipmentMode.OFF, legacy.specialEquipmentMode)
        assertEquals(allKinds, legacy.specialEquipmentKinds)
        assertEquals(50, legacy.playerWeight)
        assertEquals(LabyrinthStrategySettings(), LabyrinthStrategySettingsCodec.decode("{}"))
        // 只有勾选集合、没有模式的旧数据同样不得变成「自动穿戴」。
        val kindsOnly = LabyrinthStrategySettingsCodec.decode("{\"specialEquipmentKinds\":[\"EX\"]}")
        assertEquals(LabyrinthSpecialEquipmentMode.OFF, kindsOnly.specialEquipmentMode)
        LabyrinthCombatKind.entries.forEach { assertFalse("$it", kindsOnly.autoEquipsSpecialEquipment(it)) }
    }

    @Test fun `auto equip selection survives both codecs`() {
        val settings = LabyrinthStrategySettings(
            specialEquipmentMode = LabyrinthSpecialEquipmentMode.AUTO_EQUIP,
            specialEquipmentKinds = setOf(LabyrinthCombatKind.EX, LabyrinthCombatKind.BOSS),
        )
        val encoded = LabyrinthStrategySettingsCodec.encode(settings)
        assertTrue(encoded.contains("specialEquipmentMode"))
        assertTrue(encoded.contains("specialEquipmentKinds"))
        assertEquals(settings, LabyrinthStrategySettingsCodec.decode(encoded))
        assertEquals(setOf(LabyrinthCombatKind.EX, LabyrinthCombatKind.BOSS),
            LabyrinthStrategySettingsCodec.decode(encoded).specialEquipmentKinds)
        // 设置页草稿用另一对入口，必须同样往返一致。
        assertEquals(settings, LabyrinthStrategySettingsCodec.decodeDraft(LabyrinthStrategySettingsCodec.encodeDraft(settings)))
    }

    @Test fun `unknown future fields stay tolerated`() {
        val decoded = LabyrinthStrategySettingsCodec.decode(
            "{\"specialEquipmentMode\":\"AUTO_EQUIP\",\"specialEquipmentKinds\":[\"BOSS\"],\"specialEquipmentWhatever\":true}",
        )
        assertEquals(LabyrinthSpecialEquipmentMode.AUTO_EQUIP, decoded.specialEquipmentMode)
        assertEquals(setOf(LabyrinthCombatKind.BOSS), decoded.specialEquipmentKinds)
    }

    @Test fun `auto equip with an empty selection blocks saving with the documented error`() {
        val blocked = LabyrinthStrategySettings(
            specialEquipmentMode = LabyrinthSpecialEquipmentMode.AUTO_EQUIP,
            specialEquipmentKinds = emptySet(),
        )
        assertEquals(
            "已开启特别装备自动穿戴，请至少勾选一个关卡类型（普通/EX/BOSS）",
            blocked.validationError(),
        )
        assertEquals(LabyrinthStrategySettings.SPECIAL_EQUIPMENT_EMPTY_KINDS_ERROR, blocked.validationError())
        // 保存按钮继承的正是这条校验：草稿变了，但仍然不可保存。
        assertFalse(blocked.canSaveDraft(LabyrinthStrategySettings(), saving = false))
        assertThrows(IllegalArgumentException::class.java) { LabyrinthStrategySettingsCodec.encode(blocked) }
        // 只差一个勾选就能保存，证明上面的禁止来自空集合本身，不是权重或草稿比较。
        val fixed = blocked.copy(specialEquipmentKinds = setOf(LabyrinthCombatKind.EX))
        assertNull(fixed.validationError())
        assertTrue(fixed.canSaveDraft(LabyrinthStrategySettings(), saving = false))
        assertFalse("保存中不得再次提交", fixed.canSaveDraft(LabyrinthStrategySettings(), saving = true))
    }

    @Test fun `every non empty combination of stage kinds is legal and they do not exclude each other`() {
        val combinations = (1..7).map { mask ->
            allKinds.filterIndexed { index, _ -> mask shr index and 1 == 1 }.toSet()
        }
        assertEquals(7, combinations.size)
        combinations.forEach { kinds ->
            val draft = LabyrinthStrategySettings(
                specialEquipmentMode = LabyrinthSpecialEquipmentMode.AUTO_EQUIP,
                specialEquipmentKinds = kinds,
            )
            assertNull("$kinds 必须合法", draft.validationError())
            assertTrue("$kinds 必须可保存", draft.canSaveDraft(LabyrinthStrategySettings(), saving = false))
            assertEquals(kinds, draft.specialEquipmentKinds)
            assertEquals(kinds, LabyrinthStrategySettingsCodec.decode(LabyrinthStrategySettingsCodec.encode(draft)).specialEquipmentKinds)
        }
        assertTrue("三个多选项必须能同时勾选", combinations.contains(allKinds))
    }

    @Test fun `off mode ignores the stage kind selection and never executes`() {
        val offWithKinds = LabyrinthStrategySettings(
            specialEquipmentMode = LabyrinthSpecialEquipmentMode.OFF,
            specialEquipmentKinds = setOf(LabyrinthCombatKind.EX),
        )
        LabyrinthCombatKind.entries.forEach { assertFalse("$it", offWithKinds.autoEquipsSpecialEquipment(it)) }
        // 「关闭」时勾选集合没有语义：空集合在这里不是校验错误（错误只属于「自动穿戴」）。
        val offEmpty = offWithKinds.copy(specialEquipmentKinds = emptySet())
        assertNull(offEmpty.validationError())
        assertTrue(offEmpty.canSaveDraft(offWithKinds, saving = false))
    }

    @Test fun `the shared predicate decides per kind and an empty selection never means all kinds`() {
        val empty = LabyrinthStrategySettings(
            specialEquipmentMode = LabyrinthSpecialEquipmentMode.AUTO_EQUIP,
            specialEquipmentKinds = emptySet(),
        )
        LabyrinthCombatKind.entries.forEach { assertFalse("$it 必须被判为不执行", empty.autoEquipsSpecialEquipment(it)) }

        val exOnly = empty.copy(specialEquipmentKinds = setOf(LabyrinthCombatKind.EX))
        assertTrue(exOnly.autoEquipsSpecialEquipment(LabyrinthCombatKind.EX))
        assertFalse(exOnly.autoEquipsSpecialEquipment(LabyrinthCombatKind.NORMAL))
        assertFalse(exOnly.autoEquipsSpecialEquipment(LabyrinthCombatKind.BOSS))

        // 读到「自动穿戴 + 空集合」这种读不懂的数据时回退默认（关闭），绝不悄悄改写成「全部关卡」。
        assertEquals(
            LabyrinthStrategySettings(),
            LabyrinthStrategySettingsCodec.decode("{\"specialEquipmentMode\":\"AUTO_EQUIP\",\"specialEquipmentKinds\":[]}"),
        )
    }

    @Test fun `the settings screen reads the same field and the same rule as the automation`() {
        val screen = mainSource("ui/labyrinth/LabyrinthStrategySettingsScreen.kt")
        assertTrue("勾选状态必须来自 autoEquipsSpecialEquipment", screen.contains("draft.autoEquipsSpecialEquipment("))
        assertTrue(screen.contains("draft.specialEquipmentKinds"))
        assertTrue(screen.contains("draft.specialEquipmentMode"))
        assertTrue("保存按钮必须复用被单测钉住的 canSaveDraft", screen.contains("canSaveDraft("))
        assertTrue(
            "「关闭」时三个多选项必须不可见",
            screen.contains("if (draft.specialEquipmentMode == LabyrinthSpecialEquipmentMode.AUTO_EQUIP)"),
        )
        assertFalse(
            "界面不得另写一份关卡集合规则",
            Regex("""\b(listOf|setOf|arrayOf)\(\s*LabyrinthCombatKind""").containsMatchIn(screen),
        )
    }

    @Test fun `the visible copy matches the documented wording`() {
        val screen = mainSource("ui/labyrinth/LabyrinthStrategySettingsScreen.kt")
        listOf("特别装备", "关闭", "自动穿戴").forEach { label ->
            assertTrue("设置页必须出现「$label」", screen.contains(label))
        }
        val labels = Regex("""LabyrinthCombatKind\.(NORMAL|EX|BOSS)\s*->\s*"([^"]*)"""")
            .findAll(screen).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(mapOf("NORMAL" to "普通", "EX" to "EX", "BOSS" to "BOSS"), labels)
    }

    /** 本测试要读主源码：测试工作目录是 `android/app`，也可能是仓库根目录。 */
    private fun mainSource(relative: String): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "android/app/src/main/java").isDirectory || File(it, "app/src/main/java").isDirectory }
        val base = if (File(root, "android").isDirectory) File(root, "android") else root
        return File(base, "app/src/main/java/com/landosol/toolbox/$relative").readText()
    }
}
