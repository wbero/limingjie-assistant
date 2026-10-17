package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionDecision
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionKind
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionPlanner
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionPlannerConfig
import com.landosol.toolbox.labyrinth.LabyrinthCombatContext
import com.landosol.toolbox.labyrinth.LabyrinthCombatKind
import com.landosol.toolbox.labyrinth.labyrinthBossSettlementNextButtonRect
import com.landosol.toolbox.labyrinth.labyrinthSessionBlockObservation
import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.gamedata.CharacterResource
import com.landosol.toolbox.gamedata.GameIconPackDocument
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class LabyrinthEntryFrameProcessorTest {
    private val projectRoot = locateProjectRoot()
    private val uiRoot = File(projectRoot, "素材/ui")
    private val cropRoot = File(uiRoot, "裁剪后ui")
    private val entryRoot = File(uiRoot, "黎明界进入")
    private val assetRoot = File(
        projectRoot,
        "android/app/src/main/assets/resource-packs/cn-bilibili/vision",
    )
    private val cleanupRoot = File(projectRoot, "landosol-workspace-cleanup-20260819")
    private val processor by lazy(::createProcessor)

    @Test
    fun dateChangePopupWinsGenericSessionErrorOnCurrentClient() {
        val frame = readImage(
            File(
                projectRoot,
                "android/app/src/test/resources/labyrinth/date-change-popup-20260910.jpg",
            ),
        )

        val result = processor.process(frame)
        val dateTitle = result.observation.anchorScores[EntryAnchorId.SESSION_DATE_CHANGE_TITLE]
        val dateConfirm = result.observation.anchorScores[EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM]
        val genericError = result.observation.anchorScores[EntryAnchorId.SESSION_ERROR_TITLE]

        assertTrue("dateTitle=$dateTitle", dateTitle >= 0.90)
        assertTrue("dateConfirm=$dateConfirm", dateConfirm >= 0.90)
        assertTrue("genericError=$genericError", genericError >= 0.70)

        val block = labyrinthSessionBlockObservation(result)
        assertEquals(SessionBlockKind.RELOGIN_REQUIRED, block.kind)
        assertEquals("确认", block.actionLabel)
        assertEquals(
            result.anchorMatches[EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM]?.rect,
            block.returnTitleRect,
        )
    }

    @Test
    fun `current boss three-team win summary exposes dedicated next anchor`() {
        val screenshot = File(
            projectRoot,
            "android/app/src/test/resources/labyrinth/boss-win-summary-20260911.png",
        )
        val result = processor.process(readImage(screenshot))
        val legacyScore = result.observation.anchorScores[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON]
        val bossScore = result.observation.anchorScores[EntryAnchorId.BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON]
        val bossMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON]

        assertTrue("legacy=$legacyScore boss=$bossScore", legacyScore < 0.40)
        assertTrue("legacy=$legacyScore boss=$bossScore", bossScore >= 0.95)
        assertEquals(
            bossMatch?.rect,
            labyrinthBossSettlementNextButtonRect(
                pageState = result.observation.state,
                combatContext = LabyrinthCombatContext(LabyrinthCombatKind.BOSS),
                nextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON],
                bossSummaryNextButtonMatch = bossMatch,
            ),
        )
    }

    @Test fun `boss selected nephy stays recognizable in member strip`() {
        val frame = readImage(File(projectRoot, "android/app/src/test/resources/labyrinth/boss-member-nephy-20260910.jpg"))
        val templates = loadBattleTeamTemplates()
        val recognizer = LabyrinthBattleTeamRecognizer(templates)
        val observation = recognizer.recognize(frame)
        assertEquals(listOf("1297", "1275", "1339"), observation.selectedCharacters.map { it.characterId })
        assertTrue(observation.selectedCharacters.all { it.trusted && it.rivalMargin >= LABYRINTH_CHARACTER_ICON_SAFE_RIVAL_MARGIN })
        assertEquals(observation.selectedCharacters.map { it.characterId },
            recognizer.recognize(frame).selectedCharacters.map { it.characterId })
        val missingTemplate = LabyrinthBattleTeamRecognizer(templates.filterNot { it.characterId == "1297" })
            .recognize(frame)
        assertEquals("without identity evidence the member must stay unknown", null,
            missingTemplate.selectedCharacters.first().characterId)
    }

    @Test fun `real third boss tab owns start even when its own member strip is empty`() {
        val frame = readImage(File(projectRoot, "android/app/src/test/resources/labyrinth/boss-team-third-20260909.jpg"))
        val result = processor.process(frame)
        assertEquals(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION, result.observation.state)
        val observation = requireNotNull(result.battleTeamSelection)
        assertEquals(3, observation.bossTeamIndex)
        assertTrue(observation.selectedCharacters.isEmpty())
        val start = requireNotNull(result.anchorMatches[EntryAnchorId.BATTLE_TEAM_START_BUTTON])
        assertTrue("start=$start", start.score >= 0.66)
        val step = com.landosol.toolbox.labyrinth.labyrinthBossSingleTeamExecutionStep(observation, listOf("1044"),start.rect)
        assertTrue(step != null)
    }

    @Test
    fun `boss team editor has its own filter and clipped roster layout`() {
        val frame = readImage(File(projectRoot, "android/app/src/test/resources/labyrinth/boss-team-editor-20260909.jpg"))
        val recognizer = LabyrinthBattleTeamRecognizer(templates = loadBattleTeamTemplates(), requiredStableFrames = 1)
        val observation = recognizer.recognize(frame)
        assertEquals(1, observation.bossTeamIndex)
        assertEquals(LabyrinthBattleElementFilter.ALL, observation.currentFilter)
        assertTrue(observation.filters.all { it.screenRect.top in 275..290 })
        assertTrue(observation.selectedCharacters.isEmpty())
        assertTrue(observation.visibleCharacters.size >= 8)
        assertTrue(observation.visibleCharacters.all { it.screenRect.top >= 360 && it.screenRect.top + it.screenRect.height <= 746 })
        assertTrue(observation.scrollbar.trackRect.top in 370..385)
        val first = observation.visibleCharacters.minBy { it.screenRect.top * 2000 + it.screenRect.left }
        assertEquals("1044", first.characterId)
        val nextFrame = recognizer.recognize(frame)
        assertEquals(1, nextFrame.bossTeamIndex)
        assertEquals(observation.viewportRevision, nextFrame.viewportRevision)
    }

    @Test
    fun `reported boss roster frame measures a mid list scrollbar thumb`() {
        // 2026-09-14 debug bundle: the automation reported 无需滚动 / EXHAUSTED on this frame and
        // never swiped, because the grey-blue empty track matched the loose blue rule and became a
        // full-height thumb. The real thumb sits at roughly 28% of the Boss track.
        val frame = readImage(File(projectRoot, "android/app/src/test/resources/labyrinth/battle-team-roster-20260914.jpg"))
        val recognizer = LabyrinthBattleTeamRecognizer(templates = loadBattleTeamTemplates(), requiredStableFrames = 1)
        val observation = recognizer.recognize(frame)
        val scrollbar = observation.scrollbar
        assertEquals(1, observation.bossTeamIndex)
        assertEquals(LabyrinthBattleElementFilter.ALL, observation.currentFilter)
        assertTrue("scrollbar=$scrollbar", scrollbar.visible && scrollbar.canScroll)
        assertTrue("scrollbar=$scrollbar", scrollbar.position in 0.20..0.40)
        val thumb = requireNotNull(scrollbar.thumbRect)
        assertTrue("thumb=$thumb", thumb.height in 150..220)
        assertTrue("thumb=$thumb", thumb.top in 410..440)
    }

    @Test
    fun `effective effect sparse tail exposes only the real eight plus one cards`() {
        val frame = readImage(
            File(
                projectRoot,
                "android/app/src/test/resources/labyrinth/effective-scan-20260911/frame.jpg",
            ),
        )
        val recognizer = LabyrinthBattleTeamRecognizer(
            templates = loadBattleTeamTemplates(),
            requiredStableFrames = 1,
        )
        val rowDiagnostics = LabyrinthCharacterGridDetector().diagnoseRows(
            frame = frame,
            viewportReference = EntryReferenceRect(60, 230, 1800, 516),
            referenceCardSize = 195,
            referenceColumnPitch = 212,
            referenceRowPitch = 218,
            maxColumns = 8,
        )

        val observation = recognizer.recognize(frame)
        val visible = observation.visibleCharacters.sortedWith(
            compareBy<LabyrinthBattleCharacterMatch> { it.screenRect.top }
                .thenBy { it.screenRect.left },
        )

        assertEquals(LabyrinthBattleElementFilter.EFFECTIVE_EFFECT, observation.currentFilter)
        assertEquals("sparse 8+1 roster must not manufacture empty second-row slots", 9, visible.size)

        val firstRow = visible.filter { it.screenRect.top in 240..246 }
        val secondRow = visible.filter { it.screenRect.top in 458..464 }
        val geometry = visible.joinToString { "${it.slotId}:${it.screenRect}" }
        val diagnosticMessage = "geometry=$geometry diagnostics=$rowDiagnostics"
        assertEquals(diagnosticMessage, 8, firstRow.size)
        assertEquals(diagnosticMessage, 1, secondRow.size)
        assertTrue(visible.all { it.screenRect.height in 192..198 })
        assertTrue(
            "no ghost row may be snapped upward into the first-row tail: ${visible.map { it.screenRect }}",
            visible.none { it.screenRect.top in 405..417 },
        )
    }

    @Test
    fun `boss tab switch invalidates same geometry roster cache and reads selected member strip`() {
        val original = readImage(File(projectRoot, "android/app/src/test/resources/labyrinth/boss-team-editor-20260909.jpg"))
        val recognizer = LabyrinthBattleTeamRecognizer(templates = loadBattleTeamTemplates(), requiredStableFrames = 2)
        val first = recognizer.recognize(original)
        fun changedTab(index: Int): PixelImage {
            val pixels = original.pixels.copyOf()
            fun copyRect(sx: Int, sy: Int, dx: Int, dy: Int, w: Int, h: Int) {
                for (y in 0 until h) for (x in 0 until w) pixels[(dy+y)*original.width+dx+x] = original[sx+x,sy+y]
            }
            copyRect(334,150,98,150,195,65)
            copyRect(98,150,if (index == 2) 334 else 571,150,195,65)
            copyRect(334,372,122,372,195,195)
            copyRect(122,372,95,811,195,195)
            return PixelImage(original.width, original.height,pixels)
        }
        for (index in 2..3) {
            val frame = changedTab(index)
            val moving = recognizer.recognize(frame)
            assertEquals(LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY, moving.recognitionState)
            assertTrue(moving.visibleCharacters.isEmpty())
            assertEquals(index, moving.bossTeamIndex)
            val stable = recognizer.recognize(frame)
            assertEquals(index, stable.bossTeamIndex)
            assertTrue(stable.viewportRevision > first.viewportRevision)
            assertEquals("1078", stable.visibleCharacters.first().characterId)
            assertEquals(listOf("1044"), stable.selectedCharacters.map { it.characterId })
        }
        val ordinary = readImage(File(entryRoot, "当前版本_节点选择.png"))
        assertEquals(null, recognizer.detectBossTeamIndex(ordinary))
    }

    private fun createProcessor(): LabyrinthEntryFrameProcessor =
        LabyrinthEntryFrameProcessor(
            templates = loadTemplates(),
            characterRecognizer = LabyrinthCharacterRecognizer(loadCharacterTemplates()),
            battleTeamRecognizer = LabyrinthBattleTeamRecognizer(loadBattleTeamTemplates()),
            linkChoiceRecognizer = LabyrinthLinkChoiceRecognizer(loadLinkChoiceTemplates()),
            relicChoiceRecognizer = LabyrinthRelicRecognizer(loadRelicTemplates()),
            shopRecognizer = LabyrinthShopRecognizer(
                buyButtonTemplate = readImage(File(assetRoot, "shop/shop_buy_button.png")),
                relicTemplates = loadShopRelicTemplates(),
            ),
        )

    @Test
    fun `relic effect result popup is recognized and offers its own close button`() {
        // 「遗物效果结果」: a relic turned a lost battle into a won one. It is the same 关闭 family
        // as 获得道具 / 迷宫遗物效果 but a short centred dialog, so neither existing title pair nor
        // the full-height close anchor can see it. Unrecognised it left the map visible behind
        // itself with no rule able to close it.
        val frame = readImage(
            File(projectRoot, "android/app/src/test/resources/labyrinth/relic-effect-result-20260922.png"),
        )
        val result = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates()).process(frame)

        assertEquals(LabyrinthEntryPageState.ITEM_REWARD, result.observation.state)
        val title = result.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_RESULT_TITLE]
        val close = result.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE]
        assertTrue("title=$title", title >= 0.80)
        assertTrue("close=$close", close >= 0.80)

        // The tap must land on this dialog's own button, not on the full-height one's position.
        val rect = requireNotNull(result.anchorMatches[EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE]?.rect)
        val centerY = rect.top + rect.height / 2
        assertTrue("close rect=$rect", centerY in 700..800)
        assertTrue("close rect=$rect", rect.left + rect.width / 2 in 900..1020)

        // It must not be mistaken for the opening 迷宫遗物效果 page, which is a different popup.
        val openingRelic = result.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_TITLE]
        assertTrue("opening relic page must not claim this frame: $openingRelic", openingRelic < 0.50)

        // The dangerous failure this popup actually caused upstream: its 关闭 scores 0.81 against
        // the session date-change 确认 template and its blue bar 0.62 against 错误提示, so the frame
        // was read as "account session expired". Worse, SESSION_RETURN_TITLE's ROI lies directly on
        // this button at 0.434 against a 0.45 trigger -- one frame of jitter and the run taps it
        // and continues believing it is logged out, which throws the whole run away.
        val block = labyrinthSessionBlockObservation(result)
        assertEquals("session block=$block", SessionBlockKind.NONE, block.kind)
        assertTrue("must not offer a return-title tap: $block", block.returnTitleRect == null)
    }

    @Test
    fun `node scan is skipped while the session cannot consume it and keeps the rest of the frame`() {
        var consumable = false
        val processor = LabyrinthEntryFrameProcessor(
            templates = loadShippedTemplates(),
            nodeTemplates = com.landosol.toolbox.labyrinth.node.NodeTemplateSet(mapOf(
                "node.boss.platform" to readImage(File(assetRoot, "node_boss_platform.png")),
            )),
            nodeScanConsumable = { consumable },
        )
        val frame = readImage(File(projectRoot, "android/app/src/test/resources/labyrinth/final-boss-active-20260909.jpg"))
        val deferred = processor.process(frame)
        assertEquals(LabyrinthEntryPageState.NODE_SELECTION, deferred.observation.state)
        assertEquals(NODE_SEARCH_MODE_DEFERRED, deferred.nodeSearchMode)
        assertEquals(0L, deferred.stageMillis["nodes"])
        assertEquals(0, deferred.nodeSearchWindowCount)
        assertTrue(deferred.nodeClassifications.isEmpty())
        // Page-level recognition still ran: the frame is fully classified, only the map scan is gone.
        assertTrue(deferred.matchedFeatures.isNotEmpty())
        assertTrue((deferred.stageMillis["pageAndOther"] ?: 0L) > 0L)

        consumable = true
        val scanned = processor.process(frame)
        assertEquals("full", scanned.nodeSearchMode)
        assertTrue(scanned.nodeSearchWindowCount > 0)
    }

    @Test
    fun `route proven final boss bypasses ordinary scans and returns to normal mode afterwards`() {
        var finalBoss = true
        val fastProcessor = LabyrinthEntryFrameProcessor(
            templates = loadTemplates(),
            nodeTemplates = com.landosol.toolbox.labyrinth.node.NodeTemplateSet(mapOf(
                "node.boss.platform" to readImage(File(assetRoot, "node_boss_platform.png")),
            )),
            finalBossOnly = { finalBoss },
        )
        val frame = readImage(File(projectRoot, "android/app/src/test/resources/labyrinth/final-boss-active-20260909.jpg"))
        val first = fastProcessor.process(frame)
        assertEquals(LabyrinthEntryPageState.NODE_SELECTION, first.observation.state)
        assertEquals("final-boss-platform", first.nodeSearchMode)
        assertEquals(0L, first.stageMillis["nodes"])
        assertTrue(first.nodeClassifications.isEmpty())
        assertEquals(1, first.finalBossPlatforms.size)
        println("final-boss fast stageMillis=${first.stageMillis}")

        val otherPage = fastProcessor.process(readImage(File(entryRoot, "进入游戏加载中画面.jpg")))
        assertEquals("none", otherPage.nodeSearchMode)
        assertTrue(otherPage.finalBossPlatforms.isEmpty())
        assertTrue(otherPage.finalBossPlatformCandidates.isEmpty())

        finalBoss = false
        val normal = fastProcessor.process(frame)
        assertEquals("full", normal.nodeSearchMode)
    }

    @Test
    fun `classifies every supplied entry flow reference screenshot`() {
        val fixtures = linkedMapOf(
            "点击屏幕开始游戏.jpg" to LabyrinthEntryPageState.TITLE_WAITING_TAP,
            "进入游戏加载中画面.jpg" to LabyrinthEntryPageState.GAME_LOADING_PROGRESS,
            "进入游戏后主页前加载界面.jpg" to LabyrinthEntryPageState.PRE_HOME_DATA_LOADING,
            "进入主页前公告栏.jpg" to LabyrinthEntryPageState.HOME_ANNOUNCEMENT,
            "主页.png" to LabyrinthEntryPageState.HOME,
            "冒险页.jpg" to LabyrinthEntryPageState.ADVENTURE,
            "黎明界主页.jpg" to LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE,
            "黎明界主页_刷开局后的状态.jpg" to LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE,
            "示例_刷开局美食殿堂开局.jpg" to LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            "示例_刷开局美食殿堂开局选择三个角色.jpg" to
                LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            "角色加入.jpg" to LabyrinthEntryPageState.CHARACTER_JOINED,
            "正式进入选择节点.jpg" to LabyrinthEntryPageState.NODE_SELECTION,
            "当前版本_初始角色选择_0_3.png" to LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            "当前版本_初始角色选择_3_3.png" to LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            "当前版本_角色加入.png" to LabyrinthEntryPageState.CHARACTER_JOINED,
            "当前版本_节点选择.png" to LabyrinthEntryPageState.NODE_SELECTION,
            "当前版本_连结节点_获得道具.png" to LabyrinthEntryPageState.ITEM_REWARD,
        )

        fixtures.forEach { (fileName, expected) ->
            val result = processor.process(readImage(File(entryRoot, fileName)))
            assertEquals(
                "fixture=$fileName states=${result.observation.stateScores} anchors=${result.observation.anchorScores.values}",
                expected,
                result.observation.state,
            )
            assertTrue("fixture=$fileName", result.observation.confidence >= 0.45)
            assertTrue("fixture=$fileName", result.matchedFeatures.isNotEmpty())
        }
    }

    @Test
    fun `current fishing single-choice event is recognized structurally before OCR`() {
        val screenshot = File(
            projectRoot,
            "android/app/src/test/resources/labyrinth/event-single-choice-fishing-20260911.png",
        )
        val result = processor.process(readImage(screenshot))
        val trigger = result.observation.anchorScores[EntryAnchorId.EVENT_SINGLE_CHOICE_TRIGGER_TITLE]
        val select = result.observation.anchorScores[EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON]

        assertTrue("trigger=$trigger", trigger >= 0.90)
        assertTrue("select=$select", select >= 0.90)
        assertEquals(LabyrinthEntryPageState.EVENT_CHOICE, result.observation.state)
    }

    @Test
    fun `live purchase confirmation recognizes current modal offset and purchased relic`() {
        val source = File(projectRoot, "android/app/src/test/resources/shop/live_purchase_confirmation_20260909.jpg")
        assertTrue("missing live purchase confirmation fixture: $source", source.isFile)

        val result = processor.process(readImage(source))

        assertEquals(
            "scores=${result.observation.stateScores} anchors=${result.observation.anchorScores.values}",
            LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
            result.observation.state,
        )
        assertTrue(
            "confirmation=${result.observation.anchorScores[EntryAnchorId.SHOP_PURCHASE_CONFIRMATION_TITLE]}",
            result.observation.anchorScores[EntryAnchorId.SHOP_PURCHASE_CONFIRMATION_TITLE] >= 0.45,
        )
        val confirmButton = requireNotNull(result.anchorMatches[EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON])
        assertTrue("confirmButton=$confirmButton", confirmButton.score >= 0.90)
        assertTrue("confirmButton=$confirmButton", confirmButton.rect.left in 975..983)
        assertTrue("confirmButton=$confirmButton", confirmButton.rect.top in 828..836)
        val fullCatalogShop = LabyrinthShopRecognizer(
            buyButtonTemplate = readImage(File(assetRoot, "shop/shop_buy_button.png")),
            relicTemplates = loadAllShopRelicTemplates(),
        ).recognize(
            frame = readImage(source),
            dialogState = LabyrinthShopDialogState.PURCHASE_CONFIRMATION,
        )
        assertEquals("candidate=$fullCatalogShop", "25004", fullCatalogShop.purchaseCandidate?.relicId)
        assertTrue("candidate=$fullCatalogShop", fullCatalogShop.purchaseCandidate?.recognized == true)
    }

    @Test
    fun `live shop frame distinguishes three different relic identities against the full catalog`() {
        val sourceRoot = File(projectRoot, "android/app/src/test/resources/shop")
        val frame = readImage(File(sourceRoot, "live_shop_20260909.jpg"))
        val recognizer = LabyrinthShopRecognizer(
            buyButtonTemplate = readImage(File(assetRoot, "shop/shop_buy_button.png")),
            relicTemplates = loadAllShopRelicTemplates(),
        )

        val shop = recognizer.recognize(frame)

        assertEquals(3, shop.items.size)
        assertEquals(
            shop.items.joinToString { item ->
                "${item.slotId}:${item.relicMatch?.suspectedRelicId}" +
                    "@${item.relicMatch?.confidence}" +
                    "/margin=${item.relicMatch?.rivalMargin}"
            },
            listOf("14001", "25004", "15001"),
            shop.items.map { it.relicMatch?.relicId },
        )
        assertTrue(
            shop.items.joinToString { "${it.slotId}:${it.relicMatch}" },
            shop.items.all { it.relicMatch?.recognized == true },
        )
        assertTrue(
            shop.items.joinToString { "${it.slotId}:${it.status}/${it.buyButtonEnabledEvidence}" },
            shop.items.all { it.status == LabyrinthShopItemStatus.AVAILABLE },
        )
        assertTrue(
            shop.items.joinToString { "${it.slotId}:${it.buyButtonEnabledEvidence}" },
            shop.items.all { it.buyButtonEnabledEvidence >= 0.80 },
        )
    }

    @Test
    fun `portrait frame stays unknown without measuring clickable states`() {
        val portrait = PixelImage(20, 40, IntArray(800) { 0xff202020.toInt() })

        val result = processor.process(portrait)

        assertEquals(LabyrinthEntryPageState.UNKNOWN, result.observation.state)
        assertEquals("entry-frame-not-landscape", result.observation.reason)
        assertTrue(result.matchedFeatures.isEmpty())
    }

    @Test
    fun `standard selection anchors distinguish zero and complete selections`() {
        val none = processor.process(readImage(File(entryRoot, "当前版本_初始角色选择_0_3.png")))
            .observation.anchorScores
        val complete = processor.process(readImage(File(entryRoot, "当前版本_初始角色选择_3_3.png")))
            .observation.anchorScores

        assertTrue("none=$none", none[EntryAnchorId.SELECTION_COUNT_NONE_STANDARD] >= 0.90)
        assertTrue("none=$none", none[EntryAnchorId.INVITE_DISABLED_STANDARD] >= 0.90)
        assertTrue("complete=$complete", complete[EntryAnchorId.SELECTION_COUNT_COMPLETE_STANDARD] >= 0.90)
        assertTrue("complete=$complete", complete[EntryAnchorId.INVITE_ENABLED_STANDARD] >= 0.90)
        assertTrue(
            "complete=$complete",
            complete[EntryAnchorId.SELECTION_COUNT_COMPLETE_STANDARD] >
                complete[EntryAnchorId.SELECTION_COUNT_NONE_STANDARD],
        )
        assertTrue(
            "complete=$complete",
            complete[EntryAnchorId.INVITE_ENABLED_STANDARD] >
                complete[EntryAnchorId.INVITE_DISABLED_STANDARD],
        )
    }

    @Test
    fun `opening selector preserves identities and detects exact selected cards across zero and complete states`() {
        val noneFrame = readImage(File(entryRoot, "当前版本_初始角色选择_0_3.png"))
        val completeFrame = readImage(File(entryRoot, "当前版本_初始角色选择_3_3.png"))
        val warmProcessor = createProcessor()
        val before = warmProcessor.process(noneFrame).openingCharacterMatches
        val after = warmProcessor.process(completeFrame).openingCharacterMatches
        val scaledCompleteFrame = scale(completeFrame, width = 1600, height = 900)
        val changingViewport = requireNotNull(
            warmProcessor.process(scaledCompleteFrame).openingCharacterSelection,
        )
        val afterViewportChange = warmProcessor.process(scaledCompleteFrame).openingCharacterMatches
        val deselectedAgain = warmProcessor.process(
            scale(noneFrame, width = 1600, height = 900),
        ).openingCharacterMatches

        assertEquals(
            before.map(LabyrinthBattleCharacterMatch::characterId),
            after.map(LabyrinthBattleCharacterMatch::characterId),
        )
        assertTrue(before.none(LabyrinthBattleCharacterMatch::selected))
        assertEquals(
            setOf("available_row_1_1", "available_row_1_2", "available_row_1_3"),
            after.filter(LabyrinthBattleCharacterMatch::selected)
                .map(LabyrinthBattleCharacterMatch::slotId)
                .toSet(),
        )
        assertEquals(
            LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY,
            changingViewport.recognitionState,
        )
        assertEquals(
            before.take(3).map(LabyrinthBattleCharacterMatch::characterId),
            afterViewportChange.take(3).map(LabyrinthBattleCharacterMatch::characterId),
        )
        assertEquals(
            setOf("available_row_1_1", "available_row_1_2", "available_row_1_3"),
            afterViewportChange.filter(LabyrinthBattleCharacterMatch::selected)
                .map(LabyrinthBattleCharacterMatch::slotId)
                .toSet(),
        )
        assertTrue(deselectedAgain.none(LabyrinthBattleCharacterMatch::selected))
    }

    @Test
    fun `opening selector cold start does not substitute identities on selected cards`() {
        val completeFrame = readImage(File(entryRoot, "当前版本_初始角色选择_3_3.png"))
        val matches = createProcessor().process(completeFrame).openingCharacterMatches

        assertEquals(
            setOf("available_row_1_1", "available_row_1_2", "available_row_1_3"),
            matches.filter(LabyrinthBattleCharacterMatch::selected)
                .map(LabyrinthBattleCharacterMatch::slotId)
                .toSet(),
        )
        assertEquals(
            listOf("1060", "1078", "1120"),
            matches.take(3).map(LabyrinthBattleCharacterMatch::characterId),
        )
    }

    @Test
    fun `opening selector recognizes the default entry stop with a clipped trailing row`() {
        val result = processor.process(readImage(File(entryRoot, "当前版本_初始角色选择_0_3.png")))
        val opening = requireNotNull(result.openingCharacterSelection)

        assertEquals(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, result.observation.state)
        assertTrue("scrollbar=${opening.scrollbar}", opening.scrollbar.position in 0.65..<0.95)
        assertEquals(18, result.openingCharacterMatches.size)
        assertEquals(
            listOf(
                "1060", "1078", "1120", "1211", "1272", "1059", "1076", "1119",
                // The measured crop now also resolves row 2 column 2: Kokkoro (Ranger).
                "1155", "1253", "1058", "1075", "1118", "1210", "1279", "1064",
                "1207", "1351",
            ),
            result.openingCharacterMatches.map(LabyrinthBattleCharacterMatch::characterId),
        )
        result.openingCharacterMatches.forEach { match ->
            if (match.slotId.startsWith("available_row_3_")) {
                assertTrue(
                    "clipped slot=${match.slotId} rect=${match.screenRect}",
                    match.screenRect.height in 165..175,
                )
                assertTrue(
                    "clipped slot=${match.slotId} rect=${match.screenRect}",
                    match.screenRect.height < match.screenRect.width,
                )
            } else {
                assertTrue(
                    "full slot=${match.slotId} rect=${match.screenRect}",
                    match.screenRect.height in 190..200,
                )
                assertTrue(
                    "full slot=${match.slotId} rect=${match.screenRect}",
                    kotlin.math.abs(match.screenRect.height - match.screenRect.width) <= 3,
                )
            }
        }
    }

    @Test
    fun `real zero-selection frame produces a tap on the configured opening character`() {
        val frame = readImage(File(entryRoot, "当前版本_初始角色选择_0_3.png"))
        val result = createProcessor().process(frame)
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                characterSelectionClickIntervalMillis = 1L,
                openingRosterScrollIntervalMillis = 1L,
                requireConfiguredOpeningRoster = true,
            ),
        ).also { it.configureOpeningRoster(1) }

        val decision = planner.decide(
            state = result.observation.state,
            frameWidth = frame.width,
            frameHeight = frame.height,
            nowMillis = 1L,
            anchorScores = result.observation.anchorScores,
            anchorMatches = result.anchorMatches,
            openingCharacterMatches = result.openingCharacterMatches,
            openingCharacterSelection = result.openingCharacterSelection,
        )

        assertTrue("decision=$decision matches=${result.openingCharacterMatches}", decision is LabyrinthEntryActionDecision.Execute)
        decision as LabyrinthEntryActionDecision.Execute
        assertEquals(LabyrinthEntryActionKind.SELECT_INITIAL_CHARACTER, decision.kind)
        val target = result.openingCharacterMatches.single { it.characterId == "1075" }
        val tap = decision.action as AutomationAction.Tap
        assertTrue(
            tap.point.x in target.screenRect.left.toFloat()..
                (target.screenRect.left + target.screenRect.width).toFloat(),
        )
        assertTrue(
            tap.point.y in target.screenRect.top.toFloat()..
                (target.screenRect.top + target.screenRect.height).toFloat(),
        )
    }

    @Test
    fun `generic joined page recognizes the three visible characters`() {
        val result = processor.process(readImage(File(entryRoot, "当前版本_角色加入_当前现场.png")))

        assertEquals(LabyrinthEntryPageState.CHARACTER_JOINED, result.observation.state)
        assertEquals(
            listOf("伊莉亚(新年)", "花凛(炼金术师)", "纺希(万圣节)"),
            result.characterMatches.map { it.displayName },
        )
        assertTrue(result.characterMatches.all { it.confidence >= 0.55 })
    }

    @Test
    fun `current ordinary battle challenge screenshot is trusted`() {
        val screenshot = File(
            projectRoot,
            "通关视频/分析输出/当前现场_战斗挑战界面核对.png",
        )
        assumeTrue("missing local screenshot: ${screenshot.absolutePath}", screenshot.isFile)

        val result = processor.process(readImage(screenshot))

        assertEquals(LabyrinthEntryPageState.BATTLE_CHALLENGE, result.observation.state)
        assertTrue(
            "header=${result.observation.anchorScores[EntryAnchorId.BATTLE_CHALLENGE_HEADER_NORMAL]}",
            result.observation.anchorScores[EntryAnchorId.BATTLE_CHALLENGE_HEADER_NORMAL] >= 0.80,
        )
        assertTrue(
            "button=${result.observation.anchorScores[EntryAnchorId.BATTLE_CHALLENGE_BUTTON]}",
            result.observation.anchorScores[EntryAnchorId.BATTLE_CHALLENGE_BUTTON] >= 0.80,
        )
    }

    @Test
    fun `current battle team selection screenshot exposes filters scrollbar and roles`() {
        val screenshot = File(
            projectRoot,
            "通关视频/分析输出/当前现场_战斗选人界面核对.png",
        )
        assumeTrue("missing local screenshot: ${screenshot.absolutePath}", screenshot.isFile)

        val result = processor.process(readImage(screenshot))
        val team = requireNotNull(result.battleTeamSelection)

        assertEquals(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION, result.observation.state)
        assertTrue(
            "header=${result.observation.anchorScores[EntryAnchorId.BATTLE_TEAM_HEADER]}",
            result.observation.anchorScores[EntryAnchorId.BATTLE_TEAM_HEADER] >= 0.80,
        )
        assertTrue(
            "start=${result.observation.anchorScores[EntryAnchorId.BATTLE_TEAM_START_BUTTON]}",
            result.observation.anchorScores[EntryAnchorId.BATTLE_TEAM_START_BUTTON] >= 0.80,
        )
        assertEquals(LabyrinthBattleElementFilter.ALL, team.currentFilter)
        assertEquals(7, team.filters.size)
        assertTrue("scrollbar=$team", team.scrollbar.visible)
        assertTrue("visible=${team.visibleCharacters.size}", team.visibleCharacters.size >= 7)
        assertTrue(
            "selected=${team.selectedCharacters}",
            team.selectedCharacters.count { it.characterId != null } >= 3,
        )
        assertTrue(
            "selected=${team.selectedCharacters}",
            team.selectedCharacters.mapNotNull(LabyrinthBattleCharacterMatch::displayName)
                .containsAll(listOf("铃莓（新年）", "咲恋（夏日）", "纺希（万圣节）")),
        )
        val recognizedNames = (team.visibleCharacters + team.selectedCharacters)
            .mapNotNull(LabyrinthBattleCharacterMatch::displayName)
        assertTrue(
            "new role icons were not matched: $recognizedNames",
            recognizedNames.containsAll(
                listOf("铃莓（春日）", "咲恋（新年）", "花凛（炼金术师）", "伊莉亚（新年）"),
            ),
        )
    }

    @Test
    fun `expanded battle team selection search layout uses the lower card row`() {
        val screenshot = File(
            projectRoot,
            "通关视频/分析输出/安装后_战斗选人界面现场.png",
        )
        assumeTrue("missing local screenshot: ${screenshot.absolutePath}", screenshot.isFile)

        val result = processor.process(readImage(screenshot))
        val team = requireNotNull(result.battleTeamSelection)

        assertEquals(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION, result.observation.state)
        assertEquals(LabyrinthBattleElementFilter.ALL, team.currentFilter)
        assertTrue("visible=${team.visibleCharacters}", team.visibleCharacters.size >= 7)
        assertTrue(
            "selected=${team.selectedCharacters}",
            team.selectedCharacters.mapNotNull(LabyrinthBattleCharacterMatch::displayName)
                .containsAll(listOf("铃莓（新年）", "咲恋（夏日）", "纺希（万圣节）")),
        )
        val recognizedNames = (team.visibleCharacters + team.selectedCharacters)
            .mapNotNull(LabyrinthBattleCharacterMatch::displayName)
        assertTrue(
            "new role icons were not matched: $recognizedNames",
            recognizedNames.containsAll(
                listOf("铃莓（春日）", "咲恋（新年）", "花凛（炼金术师）", "伊莉亚（新年）"),
            ),
        )
    }

    @Test
    fun `live battle team grid keeps both rows after scrollbar drag`() {
        listOf(
            "current_battle_team_live.png",
            "current_battle_team_after_scroll.png",
            "current_live_new_apk_final_wait.png",
            "current_live_fresh_process_after_scroll.png",
        ).forEach { fileName ->
            val screenshot = File(cleanupRoot, fileName)
            assumeTrue("missing local screenshot: ${screenshot.absolutePath}", screenshot.isFile)

            val image = readImage(screenshot)
            var result = processor.process(image)
            repeat(2) {
                if (result.battleTeamSelection?.recognitionState ==
                    LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY
                ) {
                    result = processor.process(image)
                }
            }
            val team = requireNotNull(result.battleTeamSelection)
            val isPartialSecondRow = fileName == "current_battle_team_live.png" ||
                fileName == "current_live_fresh_process_after_scroll.png"

            assertTrue(
                "file=$fileName visible=${team.visibleCharacters} scrollbar=${team.scrollbar}",
                team.visibleCharacters.size >= if (isPartialSecondRow) 8 else 14,
            )
            assertTrue(
                "file=$fileName recognized=${team.recognizedCharacterCount} visible=${team.visibleCharacters}",
                team.recognizedCharacterCount >= if (isPartialSecondRow) 7 else 14,
            )
        }
    }

    @Test
    fun `static battle team frame is cached and changed viewport waits before rescanning`() {
        val initialImage = readImage(File(cleanupRoot, "current_battle_team_live.png"))
        val movedImage = readImage(File(cleanupRoot, "current_battle_team_after_scroll.png"))

        val initial = requireNotNull(processor.process(initialImage).battleTeamSelection)
        val repeated = requireNotNull(processor.process(initialImage).battleTeamSelection)
        assertEquals(LabyrinthBattleTeamRecognitionState.STABLE, repeated.recognitionState)
        assertEquals(initial.viewportRevision, repeated.viewportRevision)
        assertEquals(initial.visibleCharacters, repeated.visibleCharacters)

        val moving = requireNotNull(processor.process(movedImage).battleTeamSelection)
        assertEquals(LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY, moving.recognitionState)
        assertTrue(moving.visibleCharacters.isEmpty())
        assertTrue(moving.viewportRevision > initial.viewportRevision)

        val settled = requireNotNull(processor.process(movedImage).battleTeamSelection)
        assertEquals(LabyrinthBattleTeamRecognitionState.STABLE, settled.recognitionState)
        assertTrue(settled.visibleCharacters.size >= 14)
    }

    @Test
    fun `link choice page recognizes every element and exposes requested priority`() {
        val fixtures = listOf(
            "通关视频/分析输出/ADB只读回归/link_choice_fire_light_wind.png" to
                listOf(LabyrinthLinkElement.FIRE, LabyrinthLinkElement.LIGHT, LabyrinthLinkElement.WIND),
            "通关视频/分析输出/ADB只读回归/link_choice_light_dark_water.png" to
                listOf(LabyrinthLinkElement.LIGHT, LabyrinthLinkElement.DARK, LabyrinthLinkElement.WATER),
            "通关视频/分析输出/ADB只读回归/link_choice_dark_fire_wind.png" to
                listOf(LabyrinthLinkElement.DARK, LabyrinthLinkElement.FIRE, LabyrinthLinkElement.WIND),
            "通关视频/分析输出/ADB只读回归/当前连结节点三选一界面.png" to
                listOf(LabyrinthLinkElement.DARK, LabyrinthLinkElement.WATER, LabyrinthLinkElement.WIND),
        )

        fixtures.forEach { (path, expected) ->
            val result = processor.process(readImage(File(projectRoot, path)))
            val link = requireNotNull(result.linkChoiceSelection) {
                "fixture=$path state=${result.observation.state} " +
                    "anchors=${result.observation.anchorScores}"
            }
            assertEquals("fixture=$path", LabyrinthEntryPageState.LINK_CHOICE, result.observation.state)
            assertEquals("fixture=$path", expected, link.choices.map(LabyrinthLinkChoiceMatch::element))
            assertTrue("fixture=$path choices=$link", link.choices.all(LabyrinthLinkChoiceMatch::recognized))
            assertEquals("fixture=$path", "火 > 暗 > 光 > 风 > 水", link.priority.label)
            assertEquals("fixture=$path", 1, link.priority.rankOf(LabyrinthLinkElement.FIRE))
            assertEquals("fixture=$path", 5, link.priority.rankOf(LabyrinthLinkElement.WATER))
            assertEquals(
                "fixture=$path",
                expected.minBy { link.priority.rankOf(it) },
                requireNotNull(link.preferredChoice).element,
            )
        }
    }

    @Test
    fun `link choice action rect is the lower select button rather than the property icon`() {
        val result = processor.process(
            readImage(
                File(
                    projectRoot,
                    "通关视频/分析输出/ADB只读回归/link_choice_dark_fire_wind.png",
                ),
            ),
        )
        val link = requireNotNull(result.linkChoiceSelection)

        assertEquals(
            listOf(405, 960, 1515),
            link.choices.map { choice ->
                choice.selectionButtonRect.left + choice.selectionButtonRect.width / 2
            },
        )
        assertEquals(
            listOf(883, 883, 883),
            link.choices.map { choice ->
                choice.selectionButtonRect.top + choice.selectionButtonRect.height / 2
            },
        )
        link.choices.forEach { choice ->
            assertTrue(choice.selectionButtonRect.top > choice.badgeRect.top + choice.badgeRect.height)
            assertTrue(choice.selectionButtonRect.top > choice.screenRect.top)
        }
    }

    @Test
    fun `battle and clear result pages classify from playthrough frames`() {
        val fixtures = listOf(
            "通关视频/分析输出/战斗与结算帧/frame_1800.png" to LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
            "通关视频/分析输出/战斗与结算帧/frame_1875.png" to LabyrinthEntryPageState.BATTLE_RESULT,
            "通关视频/分析输出/战斗与结算帧/frame_1885.png" to LabyrinthEntryPageState.RUN_CLEAR_RESULT,
        )
        fixtures.forEach { (path, expected) ->
            val screenshot = File(projectRoot, path)
            assumeTrue("missing local screenshot: $screenshot", screenshot.isFile)

            val result = processor.process(readImage(screenshot))

            val debugScores = listOf(
                EntryAnchorId.BATTLE_IN_PROGRESS_MENU_BUTTON,
                EntryAnchorId.BATTLE_RESULT_REWARD_BANNER,
                EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON,
                EntryAnchorId.RUN_RESULT_LOGO,
                EntryAnchorId.RUN_RESULT_CLOSE_BUTTON,
            ).associateWith { result.observation.anchorScores[it] }
            assertEquals(
                "fixture=$path anchors=$debugScores",
                expected,
                result.observation.state,
            )
        }
    }

    @Test
    fun `bottom team list remains aligned across repeated captures`() {
        // 滑块到底后两行卡片处于稳定位置。逐槽校验名称，防止卡框跨行后仍以高分
        // 猜出错误角色；第二行第二槽也必须使用其完整头像，而不是上一行残片。
        val screenshot = File(
            cleanupRoot,
            "role_match_latest_127_0_0_1_5559.png",
        )
        assumeTrue("missing local screenshot: $screenshot", screenshot.isFile)

        val image = readImage(screenshot)
        var result = processor.process(image)
        repeat(2) {
            if (result.battleTeamSelection?.recognitionState ==
                LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY
            ) {
                result = processor.process(image)
            }
        }
        val team = requireNotNull(result.battleTeamSelection)
        assertEquals(
            listOf(
                "美美（万圣节）",
                "初音＆栞",
                "忍（万圣节）",
                "莉玛（灰姑娘）",
                "伊莉亚（新年）",
                "花凛（炼金术师）",
                "秋乃＆咲恋",
                "莉莉（夏日）",
                "铃莓（新年）",
                "帆稀（夏日）",
                "铃莓（春日）",
                "未奏希（万圣节）",
                "祈梨（新年）",
                "吹雪",
                "纺希（万圣节）",
                "咲恋（新年）",
            ),
            team.visibleCharacters.map(LabyrinthBattleCharacterMatch::displayName),
        )
        assertTrue("roles=${team.visibleCharacters}", team.visibleCharacters.all { it.confidence >= 0.35 })
    }

    @Test
    fun `bottom team row recognizes summer homare in the second slot`() {
        val screenshot = File(
            cleanupRoot,
            "role_match_latest_127_0_0_1_7555.png",
        )
        assumeTrue("missing local screenshot: $screenshot", screenshot.isFile)

        val image = readImage(screenshot)
        var result = processor.process(image)
        repeat(2) {
            if (result.battleTeamSelection?.recognitionState ==
                LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY
            ) {
                result = processor.process(image)
            }
        }
        val team = requireNotNull(result.battleTeamSelection)
        val target = team.visibleCharacters.singleOrNull {
            it.slotId == "available_row_2_2"
        }
        assertTrue("bottom scrollbar was not detected: ${team.scrollbar}", team.scrollbar.position >= 0.95)
        assertEquals(
            "bottom second slot must be 帆稀（夏日）; visible=${team.visibleCharacters}",
            "1318",
            target?.characterId,
        )
        assertEquals("帆稀（夏日）", target?.displayName)
        assertTrue("target=$target", requireNotNull(target).confidence >= 0.35)
    }

    @Test
    fun `star variant characters recognize on the team selection probe screenshot`() {
        // 第一行倒数第二个是涅妃＝涅菈(1297)，拥有 x11/x31 两张星级图标，
        // 修复前星级变体互相吃掉可信分差，显示为“未收录”。
        val screenshot = File(
            cleanupRoot,
            "role_match_probe_127_0_0_1_7555.png",
        )
        assumeTrue("missing local screenshot: $screenshot", screenshot.isFile)

        val image = readImage(screenshot)
        var result = processor.process(image)
        repeat(2) {
            if (result.battleTeamSelection?.recognitionState ==
                LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY
            ) {
                result = processor.process(image)
            }
        }
        val team = requireNotNull(result.battleTeamSelection)
        val recognized = (team.visibleCharacters + team.selectedCharacters)
            .map { "${it.slotId}=${it.characterId}:${it.displayName}(${"%.3f".format(it.confidence)})" }
        assertTrue(
            "1297 涅妃＝涅菈 must be recognized; slots=$recognized",
            (team.visibleCharacters + team.selectedCharacters).any { it.characterId == "1297" },
        )
    }

    @Test
    fun `relic choice page does not reuse link choice recognition`() {
        val screenshot = File(
            projectRoot,
            "通关视频/分析输出/ADB只读回归/当前迷宫遗物三选一界面.png",
        )
        assumeTrue("missing local screenshot: $screenshot", screenshot.isFile)

        val result = processor.process(readImage(screenshot))

        assertEquals(LabyrinthEntryPageState.RELIC_CHOICE, result.observation.state)
        assertTrue(
            "instruction=${result.observation.anchorScores[EntryAnchorId.RELIC_CHOICE_INSTRUCTION]}",
            result.observation.anchorScores[EntryAnchorId.RELIC_CHOICE_INSTRUCTION] >= 0.80,
        )
        assertTrue("relic page must not produce link elements", result.linkChoiceSelection == null)
        val relics = requireNotNull(result.relicChoiceSelection)
        assertEquals(
            "choices=$relics",
            listOf("14004", "12003", "15001"),
            relics.choices.map(LabyrinthRelicMatch::relicId),
        )
        assertEquals(listOf("强化", "弱体", "守备"), relics.choices.map(LabyrinthRelicMatch::attribute))
        assertTrue("relics=$relics", relics.choices.all(LabyrinthRelicMatch::recognized))
    }

    @Test
    fun `relic choice recognition follows standard canvas across common resolutions`() {
        val source = readImage(
            File(projectRoot, "通关视频/分析输出/ADB只读回归/当前迷宫遗物三选一界面.png"),
        )
        listOf(1280 to 720, 1600 to 900, 2560 to 1440).forEach { (width, height) ->
            val result = processor.process(scale(source, width, height))
            val relics = requireNotNull(result.relicChoiceSelection) {
                "size=${width}x$height state=${result.observation.state} " +
                    "scores=${result.observation.stateScores} anchors=${result.observation.anchorScores}"
            }
            assertEquals("size=${width}x$height", LabyrinthEntryPageState.RELIC_CHOICE, result.observation.state)
            assertEquals(
                "size=${width}x$height",
                listOf("14004", "12003", "15001"),
                relics.choices.map(LabyrinthRelicMatch::relicId),
            )
            assertEquals(
                "size=${width}x$height",
                listOf("强化", "弱体", "守备"),
                relics.choices.map(LabyrinthRelicMatch::attribute),
            )
        }
    }

    @Test
    fun `video shop flow recognizes the page, purchase dialog, completion, and exit dialog`() {
        val fixtures = linkedMapOf(
            "shop.png" to LabyrinthEntryPageState.SHOP,
            "purchase_confirmation.png" to LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
            "purchase_complete.png" to LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
            "exit_confirmation.png" to LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
        )
        val sourceRoot = File(projectRoot, "android/app/src/test/resources/shop")

        fixtures.forEach { (fileName, expected) ->
            val result = processor.process(readImage(File(sourceRoot, fileName)))
            assertEquals(
                "fixture=$fileName states=${result.observation.stateScores} anchors=${result.observation.anchorScores.values}",
                expected,
                result.observation.state,
            )
            assertTrue("fixture=$fileName", result.observation.confidence >= 0.45)
            val shop = requireNotNull(result.shopObservation) { "fixture=$fileName" }
            assertEquals("fixture=$fileName", 3, shop.items.size)
            if (expected == LabyrinthEntryPageState.SHOP) {
                assertTrue("fixture=$fileName shop=$shop", shop.recognizedButtonCount >= 2)
            }
            if (expected == LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION) {
                assertEquals("fixture=$fileName shop=$shop", "32001", shop.purchaseCandidate?.relicId)
                assertTrue(
                    "fixture=$fileName candidate=$shop",
                    shop.purchaseCandidate?.confidence ?: 0.0 >= 0.50,
                )
            }
        }
    }

    @Test
    fun `link choice recognition follows standard canvas across common resolutions`() {
        val source = readImage(
            File(projectRoot, "通关视频/分析输出/ADB只读回归/link_choice_dark_fire_wind.png"),
        )
        listOf(1280 to 720, 1600 to 900, 2560 to 1440).forEach { (width, height) ->
            val result = processor.process(scale(source, width, height))
            val link = requireNotNull(result.linkChoiceSelection) {
                "size=${width}x$height state=${result.observation.state} " +
                    "scores=${result.observation.stateScores} anchors=${result.observation.anchorScores}"
            }
            assertEquals("size=${width}x$height", LabyrinthEntryPageState.LINK_CHOICE, result.observation.state)
            assertEquals(
                "size=${width}x$height",
                listOf(LabyrinthLinkElement.DARK, LabyrinthLinkElement.FIRE, LabyrinthLinkElement.WIND),
                link.choices.map(LabyrinthLinkChoiceMatch::element),
            )
        }
    }

    @Test
    fun `session error popup anchors score high on the raw popup screenshot`() {
        val raw = File(uiRoot, "错误提示/原始截图/错误代码6002 连接中断 回到标题界.png")
        assumeTrue("missing local popup screenshot: $raw", raw.isFile)

        val result = processor.process(readImage(raw))

        assertTrue(
            "error=${result.observation.anchorScores[EntryAnchorId.SESSION_ERROR_TITLE]}",
            result.observation.anchorScores[EntryAnchorId.SESSION_ERROR_TITLE] >= 0.80,
        )
        assertTrue(
            "return=${result.observation.anchorScores[EntryAnchorId.SESSION_RETURN_TITLE]}",
            result.observation.anchorScores[EntryAnchorId.SESSION_RETURN_TITLE] >= 0.80,
        )
        val sessionBlock = labyrinthSessionBlockObservation(result)
        assertEquals(SessionBlockKind.RECONNECT_PROMPTED, sessionBlock.kind)
        assertTrue("return-title action target missing", sessionBlock.returnTitleRect != null)
    }

    /**
     * The entry templates exactly as the app ships them, read from the asset pack instead of the
     * external `素材/ui` crop tree that [loadTemplates] needs. Tests that only need a
     * representative page classification can run on any checkout with this.
     */
    private fun loadShippedTemplates(): LabyrinthEntryTemplateSet {
        val assetsRoot = File(projectRoot, "android/app/src/main/assets")
        val required = AndroidLabyrinthEntryTemplateLoader.TEMPLATE_PATHS
            .mapValues { (_, path) -> readImage(File(assetsRoot, path)) }
        val optional = AndroidLabyrinthEntryTemplateLoader.OPTIONAL_TEMPLATE_PATHS
            .filterValues { path -> File(assetsRoot, path).isFile }
            .mapValues { (_, path) -> readImage(File(assetsRoot, path)) }
        return LabyrinthEntryTemplateSet(required + optional)
    }

    private fun loadTemplates(): LabyrinthEntryTemplateSet {
        val paths = mapOf(
            EntryAnchorId.TITLE_LOGO to File(cropRoot, "主界面logo.bmp"),
            EntryAnchorId.TITLE_TAP_PROMPT to File(cropRoot, "点击屏幕开始游戏.bmp"),
            EntryAnchorId.LOADING_NOTICE to File(cropRoot, "游戏启动中此过程不消耗流量.bmp"),
            EntryAnchorId.LOADING_NOTICE_STANDARD to File(assetRoot, "entry_loading_notice_standard.bmp"),
            EntryAnchorId.LOADING_PROGRESS to File(cropRoot, "加载进度条.bmp"),
            EntryAnchorId.DATA_CONNECTING to File(cropRoot, "正在进行数据连接.bmp"),
            EntryAnchorId.LOADING_INDICATOR to File(cropRoot, "加载中.bmp"),
            EntryAnchorId.ANNOUNCEMENT_HEADER to File(cropRoot, "公告栏上方ui.bmp"),
            EntryAnchorId.ANNOUNCEMENT_CLOSE to File(cropRoot, "公告栏关闭按钮.bmp"),
            EntryAnchorId.HOME_LEVEL to File(cropRoot, "主页左上角玩家等级.bmp"),
            EntryAnchorId.HOME_ADVENTURE to File(uiRoot, "冒险.bmp"),
            EntryAnchorId.ADVENTURE_HEADER to File(assetRoot, "entry_adventure_header.bmp"),
            EntryAnchorId.ADVENTURE_HEADER_STANDARD to File(assetRoot, "entry_adventure_header_standard.bmp"),
            EntryAnchorId.LABYRINTH_ENTRY to File(cropRoot, "黎明界迷宫.bmp"),
            EntryAnchorId.DAWN_START to File(cropRoot, "黎明界出发.bmp"),
            EntryAnchorId.DAWN_START_STANDARD to File(assetRoot, "entry_dawn_start_standard.bmp"),
            EntryAnchorId.DAWN_ACTIVE to File(cropRoot, "挑战中.bmp"),
            EntryAnchorId.DAWN_ACTIVE_STANDARD to File(assetRoot, "entry_dawn_active_standard.bmp"),
            EntryAnchorId.DAWN_TICKET_LABEL to File(assetRoot, "entry_dawn_ticket_label.bmp"),
            EntryAnchorId.DAWN_TICKET_LABEL_STANDARD to File(assetRoot, "entry_dawn_ticket_label_standard.bmp"),
            EntryAnchorId.SELECTION_HEADER to File(cropRoot, "角色选择_标题区.bmp"),
            EntryAnchorId.SELECTION_HEADER_STANDARD to File(assetRoot, "entry_selection_header_standard.bmp"),
            EntryAnchorId.SELECTION_COUNT_NONE to File(cropRoot, "选择数量0_3.bmp"),
            EntryAnchorId.SELECTION_COUNT_NONE_STANDARD to File(assetRoot, "entry_selection_count_none_standard.bmp"),
            EntryAnchorId.SELECTION_COUNT_COMPLETE to File(cropRoot, "选择数量3_3.bmp"),
            EntryAnchorId.SELECTION_COUNT_COMPLETE_STANDARD to
                File(assetRoot, "entry_selection_count_complete_standard.bmp"),
            EntryAnchorId.INVITE_DISABLED to File(cropRoot, "去邀请_无法点击状态.bmp"),
            EntryAnchorId.INVITE_DISABLED_STANDARD to File(assetRoot, "entry_invite_disabled_standard.bmp"),
            EntryAnchorId.INVITE_ENABLED to File(cropRoot, "去邀请_可点击状态.bmp"),
            EntryAnchorId.INVITE_ENABLED_STANDARD to File(assetRoot, "entry_invite_enabled_standard.bmp"),
            EntryAnchorId.ROLE_REWARD_TITLE to
                File(assetRoot, "initial_role_reward/initial_role_reward_title.png"),
            EntryAnchorId.ROLE_REWARD_INSTRUCTION to
                File(assetRoot, "initial_role_reward/initial_role_reward_instruction.png"),
            EntryAnchorId.ROLE_REWARD_SELECT_BUTTON to
                File(assetRoot, "initial_role_reward/initial_role_reward_select_button.png"),
            EntryAnchorId.LINK_CHOICE_TITLE to
                File(assetRoot, "link_choice/link_choice_title.png"),
            EntryAnchorId.LINK_CHOICE_INSTRUCTION to
                File(assetRoot, "link_choice/link_choice_instruction.png"),
            EntryAnchorId.LINK_CHOICE_REMAINING to
                File(assetRoot, "link_choice/link_choice_remaining.png"),
            EntryAnchorId.LINK_CHOICE_CARD_SIGNATURE to
                File(assetRoot, "link_choice/link_choice_card_signature.png"),
            EntryAnchorId.RELIC_CHOICE_INSTRUCTION to
                File(assetRoot, "relic_choice/relic_choice_instruction.png"),
            EntryAnchorId.ITEM_REWARD_TITLE to File(assetRoot, "item_reward/item_reward_title.png"),
            EntryAnchorId.ITEM_REWARD_INSTRUCTION to
                File(assetRoot, "item_reward/item_reward_instruction.png"),
            EntryAnchorId.ITEM_REWARD_CLOSE to File(assetRoot, "item_reward/item_reward_close.png"),
            EntryAnchorId.SHOP_TITLE to File(assetRoot, "shop/shop_title.png"),
            EntryAnchorId.SHOP_INSTRUCTION to File(assetRoot, "shop/shop_instruction.png"),
            EntryAnchorId.SHOP_CLOSE to File(assetRoot, "shop/shop_close_button.png"),
            EntryAnchorId.SHOP_BUY_BUTTON to File(assetRoot, "shop/shop_buy_button.png"),
            EntryAnchorId.SHOP_REFRESH_BUTTON to File(assetRoot, "shop/shop_refresh_button.png"),
            EntryAnchorId.SHOP_PURCHASE_CONFIRMATION_TITLE to
                File(assetRoot, "shop/shop_purchase_confirmation_title.png"),
            EntryAnchorId.SHOP_PURCHASE_COMPLETE_TITLE to
                File(assetRoot, "shop/shop_purchase_complete_title.png"),
            EntryAnchorId.SHOP_EXIT_CONFIRMATION_TITLE to
                File(assetRoot, "shop/shop_exit_confirmation_title.png"),
            EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON to
                File(assetRoot, "shop/shop_exit_confirm_button.png"),
            EntryAnchorId.JOINED_HEADER to File(cropRoot, "角色加入标题ui.bmp"),
            EntryAnchorId.JOINED_HEADER_STANDARD to File(assetRoot, "entry_joined_header_standard.bmp"),
            EntryAnchorId.JOINED_CLOSE to File(cropRoot, "角色加入关闭按钮.bmp"),
            EntryAnchorId.JOINED_CLOSE_STANDARD to File(assetRoot, "entry_joined_close_standard.bmp"),
            EntryAnchorId.NODE_HEADER to File(cropRoot, "正式进入节点_左上黎明界迷宫和返回区域.bmp"),
            EntryAnchorId.NODE_HEADER_STANDARD to File(assetRoot, "entry_node_header_standard.bmp"),
            EntryAnchorId.NODE_CHARACTERS to File(cropRoot, "正式进入节点_左下角色按钮.bmp"),
            EntryAnchorId.NODE_CHARACTERS_STANDARD to File(assetRoot, "entry_node_characters_standard.bmp"),
            EntryAnchorId.NODE_RELICS to File(cropRoot, "正式进入节点_左下迷宫遗物按钮.bmp"),
            EntryAnchorId.NODE_RELICS_STANDARD to File(assetRoot, "entry_node_relics_standard.bmp"),
            EntryAnchorId.NODE_RETREAT to File(cropRoot, "正式进入节点_右下撤退（无报酬）按钮.bmp"),
            EntryAnchorId.NODE_RETREAT_STANDARD to File(assetRoot, "entry_node_retreat_standard.bmp"),
            EntryAnchorId.NODE_RETURN to File(cropRoot, "正式进入节点_右下返回（有报酬）按钮.bmp"),
            EntryAnchorId.NODE_RETURN_STANDARD to File(assetRoot, "entry_node_return_standard.bmp"),
            EntryAnchorId.BATTLE_CHALLENGE_HEADER_NORMAL to
                File(assetRoot, "battle_challenge/battle_challenge_header_normal.png"),
            EntryAnchorId.BATTLE_CHALLENGE_SUFFIX_NORMAL to
                File(assetRoot, "battle_challenge/battle_challenge_normal_suffix.png"),
            EntryAnchorId.BATTLE_CHALLENGE_SUFFIX_EXTREME to
                File(assetRoot, "battle_challenge/battle_challenge_extreme_suffix.png"),
            EntryAnchorId.BATTLE_CHALLENGE_BUTTON to
                File(assetRoot, "battle_challenge/battle_challenge_button.png"),
            EntryAnchorId.BATTLE_CHALLENGE_VIEW_MAP_BUTTON to
                File(assetRoot, "battle_challenge/battle_challenge_view_map_button.png"),
            EntryAnchorId.BATTLE_TEAM_HEADER to
                File(assetRoot, "battle_team_selection/battle_team_selection_header.png"),
            EntryAnchorId.BATTLE_TEAM_FILTER_BAR to
                File(assetRoot, "battle_team_selection/battle_team_selection_filter_bar.png"),
            EntryAnchorId.BATTLE_TEAM_CURRENT_MEMBERS_SUMMARY to
                File(assetRoot, "battle_team_selection/battle_team_selection_current_members_summary.png"),
            EntryAnchorId.BATTLE_TEAM_CANCEL_BUTTON to
                File(assetRoot, "battle_team_selection/battle_team_selection_cancel_button.png"),
            EntryAnchorId.BATTLE_TEAM_START_BUTTON to
                File(assetRoot, "battle_team_selection/battle_team_selection_start_button.png"),
            EntryAnchorId.BATTLE_TEAM_SPECIAL_EQUIPMENT_BUTTON to
                File(assetRoot, "special_equipment/special_equipment_battle_team_button.png"),
            EntryAnchorId.SPECIAL_EQUIPMENT_DIALOG_ANCHOR to
                File(assetRoot, "special_equipment/special_equipment_dialog_anchor.png"),
            EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_BUTTON to
                File(assetRoot, "special_equipment/special_equipment_auto_button.png"),
            EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_ENABLED to
                File(assetRoot, "special_equipment/special_equipment_equip_confirm_enabled.png"),
            EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_DISABLED to
                File(assetRoot, "special_equipment/special_equipment_equip_confirm_disabled.png"),
            EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_DIALOG_ANCHOR to
                File(assetRoot, "special_equipment/special_equipment_auto_dialog_anchor.png"),
            EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_DIALOG_CONFIRM_BUTTON to
                File(assetRoot, "special_equipment/special_equipment_auto_dialog_confirm_button.png"),
            EntryAnchorId.SESSION_ERROR_TITLE to File(uiRoot, "错误提示/错误提示.bmp"),
            EntryAnchorId.SESSION_RETURN_TITLE to File(uiRoot, "错误提示/返回标题.bmp"),
            EntryAnchorId.SESSION_DATE_CHANGE_TITLE to File(assetRoot, "session_date_change_title.png"),
            EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM to File(assetRoot, "session_date_change_confirm.png"),
            EntryAnchorId.BATTLE_IN_PROGRESS_MENU_BUTTON to
                File(assetRoot, "battle_in_progress/battle_menu_button.png"),
            EntryAnchorId.BATTLE_RESULT_REWARD_BANNER to
                File(assetRoot, "battle_result/battle_result_reward_banner.png"),
            EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON to
                File(assetRoot, "battle_result/battle_result_next_button.png"),
            EntryAnchorId.EVENT_SINGLE_CHOICE_TRIGGER_TITLE to
                File(assetRoot, "event_single_choice_trigger_title.png"),
            EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON to
                File(assetRoot, "event_single_choice_select_button.png"),
            EntryAnchorId.BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON to
                File(assetRoot, "battle_result/battle_result_boss_summary_next_button.png"),
            EntryAnchorId.RUN_RESULT_LOGO to File(assetRoot, "run_result/run_result_logo.png"),
            EntryAnchorId.RUN_RESULT_CLOSE_BUTTON to
                File(assetRoot, "run_result/run_result_close_button.png"),
        )
        return LabyrinthEntryTemplateSet(paths.mapValues { (_, file) -> readImage(file) })
    }

    private fun loadLinkChoiceTemplates(): List<LabyrinthLinkChoiceElementTemplate> = listOf(
        LabyrinthLinkChoiceElementTemplate(
            LabyrinthLinkElement.FIRE,
            readImage(File(assetRoot, "link_choice/element_fire.png")),
        ),
        LabyrinthLinkChoiceElementTemplate(
            LabyrinthLinkElement.DARK,
            readImage(File(assetRoot, "link_choice/element_dark.png")),
        ),
        LabyrinthLinkChoiceElementTemplate(
            LabyrinthLinkElement.LIGHT,
            readImage(File(assetRoot, "link_choice/element_light.png")),
        ),
        LabyrinthLinkChoiceElementTemplate(
            LabyrinthLinkElement.WIND,
            readImage(File(assetRoot, "link_choice/element_wind.png")),
        ),
        LabyrinthLinkChoiceElementTemplate(
            LabyrinthLinkElement.WATER,
            readImage(File(assetRoot, "link_choice/element_water.png")),
        ),
    )

    /**
     * Full icon competition is important for shop identity tests: a single-template fixture can
     * never expose a collapsed rival margin, which was the production failure on the live shop.
     */
    private fun loadAllShopRelicTemplates(): List<LabyrinthRelicTemplate> {
        val relicRoot = File(assetRoot.parentFile, "icons/relics")
        val files = relicRoot.listFiles { file -> file.isFile && file.extension.equals("png", true) }
            ?.sortedBy(File::getName)
            .orEmpty()
        assertTrue("missing relic templates under $relicRoot", files.isNotEmpty())
        return files.map { file ->
            val relicId = file.nameWithoutExtension
            LabyrinthRelicTemplate(
                relicId = relicId,
                displayName = relicId,
                attribute = "test",
                attributeBonus = "",
                effect = "",
                image = readImage(file),
            )
        }
    }

    private fun loadRelicTemplates(): List<LabyrinthRelicTemplate> {
        val relicRoot = File(assetRoot.parentFile, "icons/relics")
        return listOf(
            LabyrinthRelicTemplate(
                relicId = "14004",
                displayName = "蛇紋のツルギ",
                attribute = "强化",
                attributeBonus = "1",
                effect = "物理/魔法攻击力 +20%",
                image = readImage(File(relicRoot, "14004.png")),
            ),
            LabyrinthRelicTemplate(
                relicId = "12003",
                displayName = "暗剣ポワゾン",
                attribute = "弱体",
                attributeBonus = "1",
                effect = "使敌全体中毒",
                image = readImage(File(relicRoot, "12003.png")),
            ),
            LabyrinthRelicTemplate(
                relicId = "15001",
                displayName = "回生杖",
                attribute = "守备",
                attributeBonus = "1",
                effect = "回复强化 +10",
                image = readImage(File(relicRoot, "15001.png")),
            ),
        )
    }

    private fun loadShopRelicTemplates(): List<LabyrinthRelicTemplate> = listOf(
        LabyrinthRelicTemplate(
            relicId = "32001",
            displayName = "幻獣飾テンタシオン",
            attribute = "弱体",
            attributeBonus = "3",
            effect = "异常状态命中 +60",
            image = readImage(File(assetRoot.parentFile, "icons/relics/32001.png")),
        ),
    )

    private fun loadBattleTeamTemplates(): List<LabyrinthBattleCharacterTemplate> = run {
            val json = Json { ignoreUnknownKeys = true }
            val resourceRoot = assetRoot.parentFile
            val icons = json.decodeFromString<GameIconPackDocument>(File(resourceRoot, "icons.json").readText())
            val roster = json.decodeFromString<CharacterRosterDocument>(
                File(resourceRoot, "characters.landosolroster.json").readText(),
            ).characters.associateBy(CharacterResource::id)
            icons.characterIcons.filter { it.format == "png" }.mapNotNull { icon ->
                val character = roster[icon.ownerId] ?: return@mapNotNull null
                LabyrinthBattleCharacterTemplate(
                    characterId = icon.ownerId,
                    displayName = character.displayName,
                    iconVariant = icon.variant,
                    image = readImage(File(resourceRoot, icon.file)),
                )
            }
        }

    @Serializable
    private data class CharacterRosterDocument(
        val schemaVersion: Int = 1,
        val characters: List<CharacterResource> = emptyList(),
    )

    private fun loadCharacterTemplates(): LabyrinthCharacterTemplateSet = LabyrinthCharacterTemplateSet(
        listOf(
            LabyrinthCharacterTemplate(
                slotId = "role_reward_left",
                characterId = "1335",
                displayName = "咲恋(新年)",
                image = readImage(File(assetRoot, "initial_role_reward/characters/character_1335_name.png")),
            ),
            LabyrinthCharacterTemplate(
                slotId = "role_reward_center",
                characterId = "1233",
                displayName = "涅娅",
                image = readImage(File(assetRoot, "initial_role_reward/characters/character_1233_name.png")),
            ),
            LabyrinthCharacterTemplate(
                slotId = "role_reward_right",
                characterId = "1256",
                displayName = "碧卡拉",
                image = readImage(File(assetRoot, "initial_role_reward/characters/character_1256_name.png")),
            ),
            LabyrinthCharacterTemplate(
                slotId = "joined_first",
                characterId = "1209",
                displayName = "伊莉亚(新年)",
                image = readImage(File(assetRoot, "character_joined/characters/character_1209_name.png")),
            ),
            LabyrinthCharacterTemplate(
                slotId = "joined_second",
                characterId = "1257",
                displayName = "花凛(炼金术师)",
                image = readImage(File(assetRoot, "character_joined/characters/character_1257_name.png")),
            ),
            LabyrinthCharacterTemplate(
                slotId = "joined_third",
                characterId = "1139",
                displayName = "纺希(万圣节)",
                image = readImage(File(assetRoot, "character_joined/characters/character_1139_name.png")),
            ),
        ),
    )

    private fun readImage(file: File): PixelImage {
        assumeTrue("missing local image: ${file.absolutePath}", file.isFile)
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = requireNotNull(
            imageIo.getMethod("read", File::class.java).invoke(null, file),
        ) { "Cannot decode ${file.absolutePath}" }
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            IntArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }

    private fun scale(source: PixelImage, width: Int, height: Int): PixelImage {
        val pixels = IntArray(width * height)
        repeat(height) { y ->
            val sourceY = (y * source.height / height).coerceAtMost(source.height - 1)
            repeat(width) { x ->
                val sourceX = (x * source.width / width).coerceAtMost(source.width - 1)
                pixels[y * width + x] = source[sourceX, sourceY]
            }
        }
        return PixelImage(width, height, pixels)
    }

    private fun locateProjectRoot(): File {
        val candidates = generateSequence(File(".").canonicalFile, File::getParentFile).take(5)
        return candidates
            .firstOrNull { File(it, "android/app/build.gradle.kts").isFile }
            ?: error("Cannot locate project root from ${File(".").canonicalPath}")
    }

    @Test fun `stage timings keep battle-team recognition separate from page classification`() {
        // Across the 2026-09 bundles BATTLE_TEAM_SELECTION was 18% of run wall-clock and 88-94%
        // of that was our own recognition — but every millisecond of it landed in "pageAndOther"
        // together with the page classifier, so a bundle could not say which of the two to look
        // at. The buckets must stay disjoint and must add up to the frame's own elapsed time.
        val processor = createProcessor()
        val result = processor.process(
            PixelImage(1920, 1080, IntArray(1920 * 1080) { 0xff101018.toInt() }),
        )
        val stages = result.stageMillis
        assertTrue("battleTeam bucket missing: ${stages.keys}", stages.containsKey("battleTeam"))
        val parts = listOf("nodes", "bossPlatform", "battleTeam", "pageAndOther")
            .sumOf { stages[it] ?: 0L }
        assertTrue(
            "stages $stages must not exceed elapsed ${result.elapsedMillis}",
            parts <= result.elapsedMillis + 2,
        )
        assertTrue("stages must not be negative: $stages", parts >= 0 && stages.values.all { it >= 0 })
    }
}
