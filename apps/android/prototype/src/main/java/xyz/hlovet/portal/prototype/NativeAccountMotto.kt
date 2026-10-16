package xyz.hlovet.portal.prototype

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class NativeMottoLine(val zh: String, val en: String)

private val nativeMottoOpenings = listOf(
    NativeMottoLine("把今天过得认真一点", "Give today your full attention"),
    NativeMottoLine("慢一点也没关系", "It is all right to move slowly"),
    NativeMottoLine("真正的成长常常很安静", "Real growth is often quiet"),
    NativeMottoLine("保持好奇，也保持耐心", "Stay curious and patient"),
    NativeMottoLine("每一次小小的坚持都算数", "Every small act of persistence counts"),
    NativeMottoLine("允许自己从不完美开始", "Let yourself begin before you are perfect"),
    NativeMottoLine("向前走时，也别忘了照顾自己", "Take care of yourself while moving forward"),
    NativeMottoLine("答案会在行动里逐渐清晰", "Action will make the answer clearer"),
    NativeMottoLine("把注意力放回能改变的事情", "Bring your focus back to what you can change"),
    NativeMottoLine("日子需要热爱，也需要节奏", "Life needs both passion and rhythm"),
    NativeMottoLine("你的步伐不必与别人一致", "Your pace does not need to match anyone else's"),
    NativeMottoLine("那些看似普通的积累终会发光", "Ordinary effort will shine in time"),
    NativeMottoLine("先相信自己值得更好的生活", "First believe you deserve a better life"),
    NativeMottoLine("认真生活的人自带光芒", "People who live earnestly carry their own light"),
    NativeMottoLine("沉住气，时间会给出回响", "Stay steady and let time answer"),
    NativeMottoLine("别低估重复一件小事的力量", "Do not underestimate the power of small repetition"),
    NativeMottoLine("清醒地选择，温柔地坚持", "Choose clearly and persist gently"),
    NativeMottoLine("生活不会辜负每一份用心", "Life does not waste sincere effort"),
    NativeMottoLine("把复杂的事拆成简单的一步", "Break a hard thing into one simple step"),
    NativeMottoLine("每个清晨都是一次重新出发", "Every morning is a fresh start"),
    NativeMottoLine("不急着证明，也不停止成长", "Do not rush to prove yourself or stop growing"),
    NativeMottoLine("在自己的节奏里稳稳前进", "Move steadily at your own pace"),
    NativeMottoLine("心里有方向，脚下就有力量", "A clear direction gives your steps strength"),
    NativeMottoLine("给梦想一点时间，也给自己一点耐心", "Give your dreams time and yourself patience"),
    NativeMottoLine("专注当下，就是最可靠的前进", "Focusing on today is the surest progress"),
    NativeMottoLine("你走过的路都在塑造更好的自己", "Every road you take shapes a better you"),
    NativeMottoLine("保持真诚，也保持一点勇敢", "Stay sincere and keep a little courage"),
    NativeMottoLine("值得做的事，慢慢做也会抵达", "Good work still gets there when done slowly"),
    NativeMottoLine("今天的努力会成为明天的底气", "Today's effort becomes tomorrow's confidence"),
    NativeMottoLine("世界很大，先把自己的生活过好", "The world is vast, so first live your own life well"),
    NativeMottoLine("愿你有重新开始的勇气", "May you have the courage to begin again"),
    NativeMottoLine("平凡的日子也可以被认真点亮", "Ordinary days can be lit by care"),
)

private val nativeMottoEndings = listOf(
    NativeMottoLine("先完成眼前最小的一步", "Start with the smallest step in front of you"),
    NativeMottoLine("把能做好的事情认真做好", "Do the things within reach with care"),
    NativeMottoLine("让行动替你回答犹豫", "Let action answer your hesitation"),
    NativeMottoLine("给自己一个温柔而坚定的回应", "Offer yourself a kind and steady answer"),
    NativeMottoLine("今天也比昨天更靠近目标一点", "Get one step closer to your goal than yesterday"),
    NativeMottoLine("把耐心留给正在成长的自己", "Save patience for the person you are becoming"),
    NativeMottoLine("不必着急，稳稳走就是答案", "There is no need to hurry; steady is enough"),
    NativeMottoLine("让每一次尝试都成为新的经验", "Let every attempt become new experience"),
    NativeMottoLine("记得为自己的进步留一点掌声", "Remember to applaud your own progress"),
    NativeMottoLine("把时间花在真正重要的人和事上", "Spend time on the people and things that matter"),
    NativeMottoLine("即使很慢，也不要停下脚步", "Even when it is slow, keep going"),
    NativeMottoLine("愿努力有回音，等待有结果", "May effort find an answer and waiting find a result"),
    NativeMottoLine("先出发，再一路修正方向", "Set out first, then refine your direction"),
    NativeMottoLine("用清醒的头脑守住内心的热爱", "Use a clear mind to protect what you love"),
    NativeMottoLine("让好习惯替你积累看得见的改变", "Let good habits build visible change"),
    NativeMottoLine("在有限的时间里创造属于自己的意义", "Create your own meaning in the time you have"),
    NativeMottoLine("把今天能完成的事留在今天", "Finish what you can today, today"),
    NativeMottoLine("走自己的路，也欣赏沿途的风景", "Walk your own path and enjoy its scenery"),
    NativeMottoLine("带着热爱前行，也给生活留些余地", "Move with passion and leave room for life"),
    NativeMottoLine("允许偶尔停下，但别忘了再次启程", "Pause when needed, then remember to begin again"),
    NativeMottoLine("用长期主义对待真正想要的生活", "Treat the life you want with long-term care"),
    NativeMottoLine("把难题交给时间，也交给持续的行动", "Give hard problems both time and continued action"),
    NativeMottoLine("认真感受当下，也期待下一次相遇", "Feel this moment deeply and welcome the next"),
    NativeMottoLine("保持一点锋芒，也保留足够的温柔", "Keep your edge while holding on to kindness"),
    NativeMottoLine("让内心安定，脚步自然会更坚定", "Settle your mind and your steps will grow firmer"),
    NativeMottoLine("多做一点积累，少给一点焦虑", "Build a little more and give anxiety less room"),
    NativeMottoLine("把选择变成行动，把行动变成结果", "Turn choices into action and action into results"),
    NativeMottoLine("珍惜每一次能够重新选择的机会", "Value every chance to choose again"),
    NativeMottoLine("愿你眼里有光，手上有事，心中有路", "May your eyes hold light, your hands hold work, and your heart hold a path"),
    NativeMottoLine("相信那些默默积累的时刻不会白费", "Trust that quiet effort is never wasted"),
    NativeMottoLine("先照顾好自己，再拥抱更大的世界", "Care for yourself first, then embrace the wider world"),
    NativeMottoLine("愿你自由、清醒，并始终忠于自己", "May you stay free, clear-minded, and true to yourself"),
)

internal fun nativeAccountMotto(accountId: Int, username: String, createdAt: String?): String {
    val seed = nativeStableHash("$accountId:$username:${createdAt.orEmpty()}").toUInt().toLong()
    val opening = nativeMottoOpenings[(seed % nativeMottoOpenings.size).toInt()]
    val endingSeed = ((seed xor 0x9e3779b9L) * 0x85ebca6bL).toUInt().toLong()
    val ending = nativeMottoEndings[(endingSeed % nativeMottoEndings.size).toInt()]
    return if (NativeI18n.locale == "en-US") {
        "${opening.en}. ${ending.en}."
    } else {
        "${opening.zh}，${ending.zh}。"
    }
}

internal fun nativeAccountMottoVariantCount(): Int = nativeMottoOpenings.size * nativeMottoEndings.size

internal fun nativeMemberDuration(createdAt: String?, nowMillis: Long = System.currentTimeMillis()): String {
    val joinedMillis = createdAt?.let { value ->
        listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd")
            .asSequence()
            .mapNotNull { pattern -> runCatching { SimpleDateFormat(pattern, Locale.US).parse(value)?.time }.getOrNull() }
            .firstOrNull()
    } ?: return "—"
    val totalSeconds = ((nowMillis - joinedMillis).coerceAtLeast(0L) / 1000L)
    val days = totalSeconds / 86400L
    val hours = (totalSeconds % 86400L) / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return nativePhrase(
        "$days 天 $hours 小时 $minutes 分 $seconds 秒",
        "${days}d ${hours}h ${minutes}m ${seconds}s",
    )
}

private fun nativeStableHash(value: String): Int {
    var hash = 2166136261L
    value.forEach { character ->
        hash = ((hash xor character.code.toLong()) * 16777619L) and 0xffffffffL
    }
    return hash.toUInt().toInt()
}
