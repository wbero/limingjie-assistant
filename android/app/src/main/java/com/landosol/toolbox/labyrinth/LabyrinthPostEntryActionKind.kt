package com.landosol.toolbox.labyrinth

/**
 * Semantic identity of a post-entry tap. The human-readable label attached to a dispatch is for
 * the overlay and the debug trace only; every commit-side transition keys on this enum.
 *
 * Phase two of the revised plan: before this, [LabyrinthEntryRecognitionSession] decided which
 * state to advance by comparing the dispatched label against Chinese UI copy such as
 * `label.startsWith("选择角色：")`. Changing a message silently broke the chain it belonged to.
 */
internal enum class LabyrinthPostEntryActionKind {
    /** Generic tap with no commit semantics (single-choice event structure, plain closes). */
    GENERIC,

    // Role reward
    SELECT_ROLE_REWARD,
    CLOSE_CHARACTER_JOINED,
    ADVANCE_CHARACTER_ACQUISITION,

    // Events
    SELECT_EVENT,
    ADVANCE_EVENT_ANIMATION,
    EVENT_FREE_ROLE_SELECT,
    EVENT_FREE_ROLE_CONFIRM,
    /** Single-button 确认 dialog with no page of its own (e.g. 无法获得报酬 after an event). */
    CONFIRM_GENERIC_DIALOG,

    // Rewards
    SELECT_RELIC,
    SELECT_LINK_IMPRINT,
    CLOSE_ITEM_REWARD,

    // Shop
    SHOP_BUY_RELIC,
    SHOP_BUY_IMPRINT,
    SHOP_CONFIRM_RELIC,
    SHOP_CONFIRM_IMPRINT,
    SHOP_CLOSE_PURCHASE_COMPLETE,
    SHOP_REFRESH,
    SHOP_CONFIRM_REFRESH,
    SHOP_CLOSE,
    SHOP_CONFIRM_EXIT,

    // Battle
    BATTLE_START_CHALLENGE,
    BATTLE_RETRY,
    BATTLE_RETRY_SWITCH_MULTI,
    /**
     * 两键战斗失败页（遗物复活变体）的右下按钮写的是「下一步」：它推进遗物效果结果弹窗，不是重新
     * 挑战，因此不消耗重试预算、不动失败队伍记账。点击矩形与 [BATTLE_RETRY] 相同（同一个按钮槽位）。
     */
    BATTLE_FAILURE_NEXT,
    BATTLE_RESULT_NEXT,
    BOSS_SETTLEMENT_NEXT,

    // EX detail probe
    EX_PROBE_DETAIL,
    EX_CLOSE_DETAIL,

    // Final settlement
    CLOSE_FINAL_SCORE,
    CLOSE_FINAL_ITEM,
    ADVANCE_FINAL_SETTLEMENT,
}

/** A planned post-entry tap: what it means, what to show, and where to tap. */
internal data class LabyrinthPostEntryTapPlan(
    val kind: LabyrinthPostEntryActionKind,
    val label: String,
    val rect: com.landosol.toolbox.labyrinth.vision.EntryPixelRect,
)
