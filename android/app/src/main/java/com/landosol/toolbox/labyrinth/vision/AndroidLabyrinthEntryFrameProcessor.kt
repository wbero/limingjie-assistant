package com.landosol.toolbox.labyrinth.vision

import android.content.Context
import com.landosol.toolbox.labyrinth.node.NodeSearchHint
import com.landosol.toolbox.labyrinth.LabyrinthCharacterAttribute
import android.graphics.BitmapFactory
import com.landosol.toolbox.automation.capture.CapturedFrame
import com.landosol.toolbox.clanbattle.recognition.AndroidPixelImageAdapter
import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.node.AndroidLabyrinthNodeTemplateLoader

class AndroidLabyrinthEntryTemplateLoader(
    private val context: Context,
) {
    fun load(): LabyrinthEntryTemplateSet {
        val required = TEMPLATE_PATHS.mapValues { (_, path) -> loadImage(path) }
        val optional = OPTIONAL_TEMPLATE_PATHS.mapNotNull { (id, path) ->
            runCatching { id to loadImage(path) }.getOrNull()
        }.toMap()
        return LabyrinthEntryTemplateSet(required + optional)
    }

    private fun loadImage(path: String): PixelImage {
        val bitmap = context.assets.open(path).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Cannot decode entry template: $path" }
        }
        return AndroidPixelImageAdapter.from(bitmap).also { bitmap.recycle() }
    }

    internal companion object {
        const val ROOT = "resource-packs/cn-bilibili/vision"
        val TEMPLATE_PATHS = mapOf(
            EntryAnchorId.TITLE_LOGO to "$ROOT/entry_title_logo.bmp",
            EntryAnchorId.TITLE_TAP_PROMPT to "$ROOT/entry_tap_prompt.bmp",
            EntryAnchorId.LOADING_NOTICE to "$ROOT/entry_loading_notice.bmp",
            EntryAnchorId.LOADING_NOTICE_STANDARD to "$ROOT/entry_loading_notice_standard.bmp",
            EntryAnchorId.LOADING_PROGRESS to "$ROOT/entry_loading_progress.bmp",
            EntryAnchorId.DATA_CONNECTING to "$ROOT/entry_data_connecting.bmp",
            EntryAnchorId.LOADING_INDICATOR to "$ROOT/entry_loading_indicator.bmp",
            EntryAnchorId.ANNOUNCEMENT_HEADER to "$ROOT/entry_announcement_header.bmp",
            EntryAnchorId.ANNOUNCEMENT_CLOSE to "$ROOT/entry_announcement_close.bmp",
            EntryAnchorId.HOME_LEVEL to "$ROOT/entry_home_level.bmp",
            EntryAnchorId.HOME_ADVENTURE to "$ROOT/adventure.bmp",
            EntryAnchorId.ADVENTURE_HEADER to "$ROOT/entry_adventure_header.bmp",
            EntryAnchorId.ADVENTURE_HEADER_STANDARD to "$ROOT/entry_adventure_header_standard.bmp",
            EntryAnchorId.LABYRINTH_ENTRY to "$ROOT/entry_labyrinth_entry.bmp",
            EntryAnchorId.DAWN_START to "$ROOT/entry_dawn_start.bmp",
            EntryAnchorId.DAWN_START_STANDARD to "$ROOT/entry_dawn_start_standard.bmp",
            EntryAnchorId.DAWN_ACTIVE to "$ROOT/entry_dawn_active.bmp",
            EntryAnchorId.DAWN_ACTIVE_STANDARD to "$ROOT/entry_dawn_active_standard.bmp",
            EntryAnchorId.DAWN_TICKET_LABEL to "$ROOT/entry_dawn_ticket_label.bmp",
            EntryAnchorId.DAWN_TICKET_LABEL_STANDARD to "$ROOT/entry_dawn_ticket_label_standard.bmp",
            EntryAnchorId.SELECTION_HEADER to "$ROOT/entry_selection_header.bmp",
            EntryAnchorId.SELECTION_HEADER_STANDARD to "$ROOT/entry_selection_header_standard.bmp",
            EntryAnchorId.SELECTION_COUNT_NONE to "$ROOT/entry_selection_count_none.bmp",
            EntryAnchorId.SELECTION_COUNT_NONE_STANDARD to "$ROOT/entry_selection_count_none_standard.bmp",
            EntryAnchorId.SELECTION_COUNT_COMPLETE to "$ROOT/entry_selection_count_complete.bmp",
            EntryAnchorId.SELECTION_COUNT_COMPLETE_STANDARD to "$ROOT/entry_selection_count_complete_standard.bmp",
            EntryAnchorId.INVITE_DISABLED to "$ROOT/entry_invite_disabled.bmp",
            EntryAnchorId.INVITE_DISABLED_STANDARD to "$ROOT/entry_invite_disabled_standard.bmp",
            EntryAnchorId.INVITE_ENABLED to "$ROOT/entry_invite_enabled.bmp",
            EntryAnchorId.INVITE_ENABLED_STANDARD to "$ROOT/entry_invite_enabled_standard.bmp",
            EntryAnchorId.ROLE_REWARD_TITLE to "$ROOT/initial_role_reward/initial_role_reward_title.png",
            EntryAnchorId.ROLE_REWARD_INSTRUCTION to
                "$ROOT/initial_role_reward/initial_role_reward_instruction.png",
            EntryAnchorId.ROLE_REWARD_SELECT_BUTTON to
                "$ROOT/initial_role_reward/initial_role_reward_select_button.png",
            EntryAnchorId.LINK_CHOICE_TITLE to "$ROOT/link_choice/link_choice_title.png",
            EntryAnchorId.LINK_CHOICE_INSTRUCTION to "$ROOT/link_choice/link_choice_instruction.png",
            EntryAnchorId.LINK_CHOICE_REMAINING to "$ROOT/link_choice/link_choice_remaining.png",
            EntryAnchorId.LINK_CHOICE_CARD_SIGNATURE to
                "$ROOT/link_choice/link_choice_card_signature.png",
            EntryAnchorId.RELIC_CHOICE_INSTRUCTION to
                "$ROOT/relic_choice/relic_choice_instruction.png",
            EntryAnchorId.ITEM_REWARD_TITLE to "$ROOT/item_reward/item_reward_title.png",
            EntryAnchorId.ITEM_REWARD_INSTRUCTION to "$ROOT/item_reward/item_reward_instruction.png",
            EntryAnchorId.ITEM_REWARD_CLOSE to "$ROOT/item_reward/item_reward_close.png",
            EntryAnchorId.SHOP_TITLE to "$ROOT/shop/shop_title.png",
            EntryAnchorId.SHOP_INSTRUCTION to "$ROOT/shop/shop_instruction.png",
            EntryAnchorId.SHOP_CLOSE to "$ROOT/shop/shop_close_button.png",
            EntryAnchorId.SHOP_BUY_BUTTON to "$ROOT/shop/shop_buy_button.png",
            EntryAnchorId.SHOP_REFRESH_BUTTON to "$ROOT/shop/shop_refresh_button.png",
            EntryAnchorId.SHOP_PURCHASE_CONFIRMATION_TITLE to
                "$ROOT/shop/shop_purchase_confirmation_title.png",
            EntryAnchorId.SHOP_PURCHASE_COMPLETE_TITLE to
                "$ROOT/shop/shop_purchase_complete_title.png",
            EntryAnchorId.SHOP_EXIT_CONFIRMATION_TITLE to
                "$ROOT/shop/shop_exit_confirmation_title.png",
            EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON to
                "$ROOT/shop/shop_exit_confirm_button.png",
            EntryAnchorId.JOINED_HEADER to "$ROOT/entry_joined_header.bmp",
            EntryAnchorId.JOINED_HEADER_STANDARD to "$ROOT/entry_joined_header_standard.bmp",
            EntryAnchorId.JOINED_CLOSE to "$ROOT/entry_joined_close.bmp",
            EntryAnchorId.JOINED_CLOSE_STANDARD to "$ROOT/entry_joined_close_standard.bmp",
            EntryAnchorId.NODE_HEADER to "$ROOT/entry_node_header.bmp",
            EntryAnchorId.NODE_HEADER_STANDARD to "$ROOT/entry_node_header_standard.bmp",
            EntryAnchorId.NODE_CHARACTERS to "$ROOT/entry_node_characters.bmp",
            EntryAnchorId.NODE_CHARACTERS_STANDARD to "$ROOT/entry_node_characters_standard.bmp",
            EntryAnchorId.NODE_RELICS to "$ROOT/entry_node_relics.bmp",
            EntryAnchorId.NODE_RELICS_STANDARD to "$ROOT/entry_node_relics_standard.bmp",
            EntryAnchorId.NODE_RETREAT to "$ROOT/entry_node_retreat.bmp",
            EntryAnchorId.NODE_RETREAT_STANDARD to "$ROOT/entry_node_retreat_standard.bmp",
            EntryAnchorId.NODE_RETURN to "$ROOT/entry_node_return.bmp",
            EntryAnchorId.NODE_RETURN_STANDARD to "$ROOT/entry_node_return_standard.bmp",
            EntryAnchorId.BATTLE_CHALLENGE_HEADER_NORMAL to
                "$ROOT/battle_challenge/battle_challenge_header_normal.png",
            EntryAnchorId.BATTLE_CHALLENGE_SUFFIX_NORMAL to
                "$ROOT/battle_challenge/battle_challenge_normal_suffix.png",
            EntryAnchorId.BATTLE_CHALLENGE_SUFFIX_EXTREME to
                "$ROOT/battle_challenge/battle_challenge_extreme_suffix.png",
            EntryAnchorId.BATTLE_CHALLENGE_BUTTON to
                "$ROOT/battle_challenge/battle_challenge_button.png",
            EntryAnchorId.BATTLE_CHALLENGE_VIEW_MAP_BUTTON to
                "$ROOT/battle_challenge/battle_challenge_view_map_button.png",
            EntryAnchorId.BATTLE_TEAM_HEADER to
                "$ROOT/battle_team_selection/battle_team_selection_header.png",
            EntryAnchorId.BATTLE_TEAM_FILTER_BAR to
                "$ROOT/battle_team_selection/battle_team_selection_filter_bar.png",
            EntryAnchorId.BATTLE_TEAM_CURRENT_MEMBERS_SUMMARY to
                "$ROOT/battle_team_selection/battle_team_selection_current_members_summary.png",
            EntryAnchorId.BATTLE_TEAM_CANCEL_BUTTON to
                "$ROOT/battle_team_selection/battle_team_selection_cancel_button.png",
            EntryAnchorId.BATTLE_TEAM_START_BUTTON to
                "$ROOT/battle_team_selection/battle_team_selection_start_button.png",
            EntryAnchorId.BATTLE_TEAM_SPECIAL_EQUIPMENT_BUTTON to
                "$ROOT/special_equipment/special_equipment_battle_team_button.png",
            EntryAnchorId.SPECIAL_EQUIPMENT_DIALOG_ANCHOR to
                "$ROOT/special_equipment/special_equipment_dialog_anchor.png",
            EntryAnchorId.SPECIAL_EQUIPMENT_DIALOG_TITLE to
                "$ROOT/special_equipment/special_equipment_dialog_title.png",
            EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_BUTTON to
                "$ROOT/special_equipment/special_equipment_auto_button.png",
            EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_ENABLED to
                "$ROOT/special_equipment/special_equipment_equip_confirm_enabled.png",
            EntryAnchorId.SPECIAL_EQUIPMENT_EQUIP_CONFIRM_DISABLED to
                "$ROOT/special_equipment/special_equipment_equip_confirm_disabled.png",
            EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_DIALOG_ANCHOR to
                "$ROOT/special_equipment/special_equipment_auto_dialog_anchor.png",
            EntryAnchorId.SPECIAL_EQUIPMENT_AUTO_DIALOG_CONFIRM_BUTTON to
                "$ROOT/special_equipment/special_equipment_auto_dialog_confirm_button.png",
            EntryAnchorId.SESSION_ERROR_TITLE to "$ROOT/session_error_title.bmp",
            EntryAnchorId.SESSION_RETURN_TITLE to "$ROOT/session_return_title.bmp",
            EntryAnchorId.SESSION_DATE_CHANGE_TITLE to "$ROOT/session_date_change_title.png",
            EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM to "$ROOT/session_date_change_confirm.png",
            EntryAnchorId.BATTLE_IN_PROGRESS_MENU_BUTTON to
                "$ROOT/battle_in_progress/battle_menu_button.png",
            EntryAnchorId.BATTLE_RESULT_REWARD_BANNER to
                "$ROOT/battle_result/battle_result_reward_banner.png",
            EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON to
                "$ROOT/battle_result/battle_result_next_button.png",
            EntryAnchorId.RUN_RESULT_LOGO to "$ROOT/run_result/run_result_logo.png",
            EntryAnchorId.RUN_RESULT_CLOSE_BUTTON to
                "$ROOT/run_result/run_result_close_button.png",
        )
        /** Optional until each page has a calibrated screenshot in the resource pack. */
        val OPTIONAL_TEMPLATE_PATHS = mapOf(
            EntryAnchorId.EVENT_CHOICE_TITLE to "$ROOT/event_choice/event_choice_title.png",
            EntryAnchorId.EVENT_CHOICE_INSTRUCTION to "$ROOT/event_choice/event_choice_instruction.png",
            EntryAnchorId.EVENT_CHOICE_OPTION to "$ROOT/event_choice/event_choice_option.png",
            EntryAnchorId.EVENT_SINGLE_CHOICE_TRIGGER_TITLE to
                "$ROOT/event_single_choice_trigger_title.png",
            EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON to
                "$ROOT/event_single_choice_select_button.png",
            EntryAnchorId.EVENT_ANIMATION_TITLE to "$ROOT/event_animation/event_animation_title.png",
            EntryAnchorId.EVENT_ANIMATION_SKIP to "$ROOT/event_animation/event_animation_skip.png",
            EntryAnchorId.RUN_CLEAR_CONGRATULATIONS_TITLE to
                "$ROOT/run_clear/run_clear_congratulations_title.png",
            EntryAnchorId.RUN_CLEAR_CHARACTER_SUMMARY_TITLE to
                "$ROOT/run_clear/run_clear_character_summary_title.png",
            EntryAnchorId.RUN_CLEAR_NEXT_BUTTON to "$ROOT/run_clear/run_clear_next_button.png",
            EntryAnchorId.RUN_CLEAR_REWARD_ANIMATION_TITLE to
                "$ROOT/run_clear/run_clear_reward_animation_title.png",
            EntryAnchorId.RUN_CLEAR_CHEST_ANIMATION_TITLE to
                "$ROOT/run_clear/run_clear_chest_animation_title.png",
            EntryAnchorId.RUN_CLEAR_CHEST_RESULT_TITLE to
                "$ROOT/run_clear/run_clear_chest_result_title.png",
            EntryAnchorId.RUN_CLEAR_CHEST_CONFIRM_BUTTON to
                "$ROOT/run_clear/run_clear_chest_confirm_button.png",
            EntryAnchorId.BATTLE_TEAM_ROSTER_EMPTY_NOTICE to
                "$ROOT/battle_team_selection/roster_empty_notice.png",
            EntryAnchorId.DAWN_HOME_MY_HOME_TAB to
                "$ROOT/dawn_home/dawn_home_my_home_tab.png",
            EntryAnchorId.RELIC_EFFECT_TITLE to "$ROOT/item_reward/relic_effect_title.png",
            EntryAnchorId.RELIC_EFFECT_INSTRUCTION to "$ROOT/item_reward/relic_effect_instruction.png",
            EntryAnchorId.RELIC_EFFECT_RESULT_TITLE to
                "$ROOT/item_reward/relic_effect_result_title.png",
            EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE to
                "$ROOT/item_reward/relic_effect_result_close.png",
            EntryAnchorId.BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON to
                "$ROOT/battle_result/battle_result_boss_summary_next_button.png",
        )
    }
}

class AndroidLabyrinthShopTemplateLoader(
    private val context: Context,
) {
    fun loadBuyButton(): PixelImage {
        val path = "resource-packs/cn-bilibili/vision/shop/shop_buy_button.png"
        val bitmap = context.assets.open(path).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Cannot decode shop template: $path" }
        }
        return AndroidPixelImageAdapter.from(bitmap).also { bitmap.recycle() }
    }
}

class AndroidLabyrinthLinkChoiceTemplateLoader(
    private val context: Context,
) {
    fun load(): List<LabyrinthLinkChoiceElementTemplate> = ELEMENTS.map { (element, path) ->
        LabyrinthLinkChoiceElementTemplate(element, loadImage(path))
    }

    private fun loadImage(path: String): PixelImage {
        val bitmap = context.assets.open(path).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Cannot decode link-choice template: $path" }
        }
        return AndroidPixelImageAdapter.from(bitmap).also { bitmap.recycle() }
    }

    private companion object {
        const val ROOT = "resource-packs/cn-bilibili/vision/link_choice"
        val ELEMENTS = linkedMapOf(
            LabyrinthLinkElement.FIRE to "$ROOT/element_fire.png",
            LabyrinthLinkElement.DARK to "$ROOT/element_dark.png",
            LabyrinthLinkElement.LIGHT to "$ROOT/element_light.png",
            LabyrinthLinkElement.WIND to "$ROOT/element_wind.png",
            LabyrinthLinkElement.WATER to "$ROOT/element_water.png",
        )
    }
}

class AndroidLabyrinthEntryFrameProcessor private constructor(
    private val processor: LabyrinthEntryFrameProcessor,
    private val exEncounterResolver: AndroidLabyrinthExEncounterResolver,
    private val eventChoiceResolver: AndroidLabyrinthEventChoiceResolver?,
    private val shopItemResolver: AndroidLabyrinthShopItemResolver,
    private val roleRewardNameResolver: AndroidLabyrinthRoleRewardNameResolver,
    private val relicStackResolver: AndroidLabyrinthRelicStackResolver,
    private val nodeRelicStackResolver: AndroidLabyrinthNodeRelicStackResolver,
    private val relicDetailResolver: AndroidLabyrinthRelicDetailResolver,
    private val skipJoinedCharacters: () -> Boolean,
) {
    fun process(frame: CapturedFrame): LabyrinthEntryFrameResult {
        val started = System.nanoTime()
        val result = processor.process(
            AndroidPixelImageAdapter.from(frame.bitmap),
            deferRoleRewardPortraits = true,
            skipJoinedCharacters = skipJoinedCharacters(),
        )
        val resolvedStarted = System.nanoTime()
        val exResolved = exEncounterResolver.resolve(frame.bitmap, result)
        val exResolvedAt = System.nanoTime()
        val eventResolved = eventChoiceResolver?.resolve(frame.bitmap, exResolved) ?: exResolved
        val shopResolved = shopItemResolver.resolve(frame.bitmap, eventResolved)
        val roleResolved = roleRewardNameResolver.resolve(frame.bitmap, shopResolved)
        val relicStarted = System.nanoTime()
        val relicResolved = relicStackResolver.resolve(frame.bitmap, roleResolved)
        val nodeStarted = System.nanoTime()
        val nodeResolved = nodeRelicStackResolver.resolve(frame.bitmap, relicResolved)
        val detailStarted = System.nanoTime()
        val resolved = relicDetailResolver.resolve(frame.bitmap, nodeResolved)
        val finished = System.nanoTime()
        return resolved.copy(
            elapsedMillis = (finished - started) / 1_000_000,
            stageMillis = result.stageMillis + mapOf(
                "exEncounter" to (exResolvedAt - resolvedStarted) / 1_000_000,
                "otherResolvers" to (relicStarted - exResolvedAt) / 1_000_000,
                "relicCurrent" to (nodeStarted - relicStarted) / 1_000_000,
                "mapCounters" to (detailStarted - nodeStarted) / 1_000_000,
                "relicDetails" to (finished - detailStarted) / 1_000_000,
                "adapterAndCore" to (resolvedStarted - started) / 1_000_000,
            ),
        )
    }

    companion object {
        fun create(
            context: Context,
            characterAttributes: Map<String, LabyrinthCharacterAttribute?> = emptyMap(),
            finalBossOnly: () -> Boolean = { false },
            skipJoinedCharacters: () -> Boolean = { false },
            nodeSearchHint: () -> NodeSearchHint? = { null },
            nodeScanRequested: () -> Boolean = { true },
            nodeScanConsumable: () -> Boolean = { true },
            rosterCharacterIds: () -> Set<String> = { emptySet() },
        ): AndroidLabyrinthEntryFrameProcessor {
            val relicTemplates = AndroidLabyrinthRelicTemplateLoader(context).load()
            val characterTemplates = AndroidLabyrinthBattleTeamTemplateLoader(context).load(characterAttributes)
            val characterIconMatcher = LabyrinthCharacterIconMatcher(characterTemplates)
            val characterRecognizer = LabyrinthCharacterRecognizer(characterIconMatcher)
            return AndroidLabyrinthEntryFrameProcessor(
                processor = LabyrinthEntryFrameProcessor(
                    templates = AndroidLabyrinthEntryTemplateLoader(context).load(),
                    nodeTemplates = AndroidLabyrinthNodeTemplateLoader(context).load(),
                    finalBossOnly = finalBossOnly,
                    nodeSearchHint = nodeSearchHint,
                    nodeScanRequested = nodeScanRequested,
                    nodeScanConsumable = nodeScanConsumable,
                    rosterCharacterIds = rosterCharacterIds,
                    characterRecognizer = characterRecognizer,
                    battleTeamRecognizer = LabyrinthBattleTeamRecognizer(
                        templates = characterTemplates,
                        iconMatcher = characterIconMatcher,
                    ),
                    linkChoiceRecognizer = LabyrinthLinkChoiceRecognizer(
                        AndroidLabyrinthLinkChoiceTemplateLoader(context).load(),
                    ),
                    relicChoiceRecognizer = LabyrinthRelicRecognizer(relicTemplates),
                    shopRecognizer = LabyrinthShopRecognizer(
                        buyButtonTemplate = AndroidLabyrinthShopTemplateLoader(context).loadBuyButton(),
                        relicTemplates = relicTemplates,
                    ),
                ),
                exEncounterResolver = AndroidLabyrinthExEncounterResolver(),
                eventChoiceResolver = runCatching {
                    AndroidLabyrinthEventChoiceResolver(
                        AndroidLabyrinthEventRecognitionDataLoader(context).load(),
                    )
                }.getOrNull(),
                shopItemResolver = AndroidLabyrinthShopItemResolver(),
                roleRewardNameResolver = AndroidLabyrinthRoleRewardNameResolver(
                    names = characterTemplates.map {
                        LabyrinthCharacterNameCandidate(it.characterId, it.displayName, it.aliases)
                    }.distinctBy { it.characterId },
                    recognizePortrait = { bitmap, slotId ->
                        characterRecognizer.recognizeRoleRewardSlot(AndroidPixelImageAdapter.from(bitmap), slotId)
                    },
                ),
                relicStackResolver = AndroidLabyrinthRelicStackResolver(),
                nodeRelicStackResolver = AndroidLabyrinthNodeRelicStackResolver(),
                relicDetailResolver = AndroidLabyrinthRelicDetailResolver(),
                skipJoinedCharacters = skipJoinedCharacters,
            )
        }
    }
}
