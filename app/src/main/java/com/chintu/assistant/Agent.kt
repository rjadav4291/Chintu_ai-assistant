package com.chintu.assistant

import java.util.Locale

class AgentStep(val skill: String, val command: String)

class AgentPlan(val goal: String, val steps: List<AgentStep>, val source: String)

// What the agent wants the screen to do: show a reply, run a plan, or ask the AI to make a plan.
class AgentAct(val reply: String? = null, val aiGoal: String? = null, val run: AgentPlan? = null)

class PlanException(message: String) : Exception(message)

object AgentRules {
    private val ic = RegexOption.IGNORE_CASE
    private val onlineSkills = setOf("weather", "wiki", "search")

    // Steps that delete, forget, clear or change things in bulk are never planned.
    private val blocked = Regex("""^(?:please\s+)?(?:delete|remove|forget|clear|erase|wipe|cancel|pause|resume|reset|uninstall)\b""", ic)
    private val strongSplit = Regex("""\s*,?\s*\b(?:and\s+then|and\s+also|then|after\s+that|afterwards)\b\s*|\s*;\s*""", ic)
    private val andSplit = Regex(
        """\s+and\s+(?=(?:remind\s+me|open\s|note\b|set\s+a\s+timer|weather|search\s|wikipedia\s|what\b|calculate|remember\s|start\s+(?:the\s+)?stopwatch))""",
        ic
    )
    private val lead = Regex("""^(?:first(?:\s+of\s+all)?|please|also|next|finally|lastly)\b[,\s]*""", ic)

    private val weatherWords = listOf("weather", "mausam", "mosam", "હવામાન", "मौसम")
    private val remindRe = Regex("""\bremind\s+me\b|\bset\s+(?:a\s+)?reminder\b""", ic)
    private val noteRe = Regex("""^(?:please\s+)?(?:(?:save|add|make|take|create)\s+(?:a\s+)?note\b|note\b)""", ic)
    private val wikiRe = Regex("""^(?:search\s+wikipedia\b|wikipedia\b|wiki\b)""", ic)
    private val searchRe = Regex("""^(?:web\s+search|search|google|look\s+up)\b""", ic)
    private val timerRe = Regex("""\btimer\b""", ic)
    private val stopwatchRe = Regex("""\bstop\s*watch\b""", ic)
    private val openRe = Regex("""^(?:please\s+)?(?:open|launch)\s+\S+""", ic)
    private val calcRe = Regex("""^(?:calculate\b|[\d\s+\-*/^().x×÷]+$|\d+(?:\.\d+)?\s*(?:%|percent)\s*of\s*\d)""", ic)
    private val timeRe = Regex(
        """\b(?:what(?:'s|\s+is)?\s+the\s+(?:time|date)|what\s+time|what\s+date|what\s+day|today'?s\s+date|current\s+(?:time|date))\b""",
        ic
    )

    // Cuts one message into separate commands.
    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        for (a in text.trim().split(strongSplit)) {
            for (b in a.split(andSplit)) {
                var t = b.trim().trim(',', '.', ';').trim()
                t = lead.replace(t, "").trim()
                if (t.isNotEmpty()) out.add(t)
            }
        }
        return out
    }

    // Returns the skill id that should handle this command, or null if it is not an allowed command.
    fun classify(part: String): String? {
        val p = part.trim()
        if (p.isEmpty() || p.length > 200) return null
        if (blocked.containsMatchIn(p)) return null
        val low = p.lowercase(Locale.ROOT)
        return when {
            remindRe.containsMatchIn(p) -> "reminders"
            noteRe.containsMatchIn(p) -> "notes"
            low.startsWith("remember ") -> "memory"
            wikiRe.containsMatchIn(p) -> "wiki"
            searchRe.containsMatchIn(p) -> "search"
            weatherWords.any { low.contains(it) } -> "weather"
            timerRe.containsMatchIn(p) -> "timer"
            stopwatchRe.containsMatchIn(p) -> "stopwatch"
            openRe.containsMatchIn(p) -> "apps"
            calcRe.containsMatchIn(p) && p.any { it.isDigit() } -> "calc"
            timeRe.containsMatchIn(p) -> "time"
            else -> null
        }
    }

    // Opening another app takes you away from Chintu, so it always goes last.
    fun reorder(steps: List<AgentStep>): List<AgentStep> =
        steps.filter { it.skill != "apps" } + steps.filter { it.skill == "apps" }

    fun validate(steps: List<AgentStep>, toolsOnline: Boolean, enabled: (String) -> Boolean): String? {
        if (steps.isEmpty()) return "There is nothing to do."
        if (steps.size > 6) return "That is more than 6 steps. Please split it into smaller requests. I did nothing."
        if (steps.count { it.skill == "apps" } > 1) return "I can open only one app per plan. I did nothing."
        for ((i, s) in steps.withIndex()) {
            if (!enabled(s.skill)) {
                return "Step ${i + 1} needs the ${SkillCatalog.nameOf(s.skill)} skill, which is turned off. Turn it on in Skills. I did nothing."
            }
            if (s.skill in onlineSkills && !toolsOnline) {
                return "Step ${i + 1} (${s.command}) needs the internet, but Private or OFFLINE mode is on. I did nothing."
            }
        }
        return null
    }

    fun effect(skill: String): String = when (skill) {
        "weather" -> "uses the internet: open-meteo.com"
        "wiki" -> "uses the internet: wikipedia.org"
        "search" -> "uses the internet: web search"
        "notes" -> "saves a note on this phone"
        "memory" -> "saves a memory on this phone"
        "reminders" -> "schedules a reminder"
        "timer" -> "starts a timer in your Clock app"
        "stopwatch" -> "changes the stopwatch"
        "apps" -> "opens another app, so it goes last"
        else -> "no side effects"
    }

    fun describe(plan: AgentPlan): String {
        val sb = StringBuilder()
        sb.append("Here is my plan (${plan.steps.size} steps, made by ${plan.source}). Say yes to run it, or no to cancel.\n")
        plan.steps.forEachIndexed { i, s -> sb.append("${i + 1}. ${s.command} (${effect(s.skill)})\n") }
        sb.append("I will stop at the first step that fails, and I will tell you exactly what was done.")
        return sb.toString()
    }
}

// Understands multi-step messages and keeps a plan waiting for your yes.
class Agent {
    var pending: AgentPlan? = null
    private val yes = setOf("yes", "y", "ok", "okay", "confirm", "sure", "run it", "haan", "ha", "han", "હા", "हाँ", "हां")
    private val no = setOf("no", "n", "cancel", "stop", "nahi", "na", "ના", "नहीं")
    private val goalRe = Regex("""^(?:agent|plan)\s*[:\-]\s*(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    // toolsOnline: the online tools (weather, wikipedia, search) may be used. aiOnline: the online AI may be used.
    fun handle(raw: String, toolsOnline: Boolean, aiOnline: Boolean, enabled: (String) -> Boolean): AgentAct? {
        val text = raw.trim()
        val low = text.lowercase(Locale.ROOT)
        val p = pending
        if (p != null) {
            pending = null
            val w = low.trim('.', '!', '?', ' ')
            if (w in yes) return AgentAct(run = p)
            if (w in no) return AgentAct(reply = "Okay, I cancelled the plan. Nothing was done.")
        }
        val goal = goalRe.find(text)
        if (goal != null) {
            if (!aiOnline) {
                return AgentAct(
                    reply = "Planning with the AI needs the online AI, and it isn't available now (OFFLINE or Private mode, or no key). You can still join clear commands with \"then\"."
                )
            }
            return AgentAct(aiGoal = goal.groupValues[1].trim())
        }
        val parts = AgentRules.split(text)
        if (parts.size < 2) return null
        val steps = mutableListOf<AgentStep>()
        for (part in parts) {
            val skill = AgentRules.classify(part) ?: return null
            steps.add(AgentStep(skill, part))
        }
        val ordered = AgentRules.reorder(steps)
        val err = AgentRules.validate(ordered, toolsOnline, enabled)
        if (err != null) return AgentAct(reply = err)
        val plan = AgentPlan(text, ordered, "simple rules")
        pending = plan
        return AgentAct(reply = AgentRules.describe(plan))
    }
}

// END OF FILE
