package com.readest.app

/**
 * 剪贴板分享链接去重标记（进程内单例，不持久化）。
 *
 * 行为契约：
 * - 场景1：App 仅切后台、进程未销毁，再次切回前台
 *   单例对象仍存活，已弹窗过的链接被记录，再次读取同链接直接静默跳过；
 *   剪贴板换为新链接才会重新弹窗并更新标记。
 * - 场景2：App 完全退出、进程彻底销毁，重新冷启动
 *   单例随进程销毁自动清空，新进程里没有历史标记，匹配到链接正常弹出询问。
 *
 * 因此无需显式 reset()，进程生命周期天然就是标记的有效期。
 */
object ClipboardMemory {
    private val shownLinks: MutableSet<String> = linkedSetOf()

    /** 该链接是否已在本次进程里弹过窗 */
    fun wasShown(link: String): Boolean = synchronized(shownLinks) {
        shownLinks.contains(link)
    }

    /** 记录已弹窗过的链接，确认 / 取消都会调用 */
    fun markShown(link: String) = synchronized(shownLinks) {
        shownLinks.add(link)
    }

    /** 显式清空（一般不需要调用，进程销毁时自动失效） */
    fun reset() = synchronized(shownLinks) {
        shownLinks.clear()
    }
}
