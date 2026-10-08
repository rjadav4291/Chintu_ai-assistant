package com.chintu.assistant

import java.util.Locale
import org.json.JSONObject

const val PLANNER_SYSTEM = """You are the planner inside a phone assistant app. You only write a plan. You never run anything.
Turn the user's request into at most 6 steps. Each step is ONE command written in English, using exactly one of these shapes:
- calculate 12 x 4
- what time is it
- what's the date
- weather in <city>
- wikipedia <topic>
- search <words>
- note: <text>
- remind me at 7 PM to <task>
- remind me in 30 minutes to <task>
- remind me tomorrow at 8 AM to <task>
- every morning at 7 AM remind me to <task>
- every Sunday at 9 AM remind me to <task>
- set a timer for 5 minutes
- remember that <fact>
- open youtube (also chrome, instagram, whatsapp, camera, calculator, maps, settings)
Rules:
- Every reminder must have a clock time with AM or PM, or "in N minutes/hours".
- Add a step only if the user asked for it. Never add notes, reminders, timers, memories or app opening on your own.
- Opening an app must be the last step.
- A step cannot use the result of an earlier step, because you will not see any results. If the request needs that, return no steps.
- Never plan deleting, forgetting, clearing, cancelling, pausing, sending messages, calling, paying or anything not listed above.
- If any part of the request cannot be done with the commands above, or a needed detail such as the city is missing, return no steps and explain in "reason" in one short sentence.
- Keep names, places and text exactly as the user wrote them, even if they are not English.
Reply with JSON only, no other text, like this:
{"steps":[{"command":"weather in Surat"},{"command":"remind me tomorrow at 8 AM to take an umbrella"}],"reason":""}"""

// Asks the online AI for a plan, then checks every step with the app's own rules before showing it.
object AgentPlanner {
    private val timeWord = Regex(
        """\b\d{1,2}(?::\d{2})?\s*(?:a\.?m\.?|p\.?m\.?)(?![a-z])|\b(?:[01]?\d|2[0-3]):[0-5]\d\b|\bin\s+\d+(?:\.\d+)?\s*(?:hours?|hrs?|minutes?|mins?)\b""",
        RegexOption.IGNORE_CASE
    )

    // A step that changes something is allowed only if the user's own words asked for that kind of thing.
    private val asked = mapOf(
        "notes" to listOf("note", "નોંધ", "नोट", "write down", "jot", "save"),
        "reminders" to listOf("remind", "reminder", "every", "alarm", "notify", "yaad", "યાદ", "याद"),
        "memory" to listOf("remember", "memory", "yaad", "યાદ", "याद"),
        "timer" to listOf("timer", "countdown", "minute", "ટાઇમર", "ટાઈમર", "टाइमर"),
        "stopwatch" to listOf("stopwatch", "stop watch", "સ્ટોપવોચ", "स्टॉपवॉच"),
        "apps" to listOf("open", "launch", "start", "play", "khol", "ખોલ", "खोल")
    )

    fun planWithAi(
        baseUrl: String,
        apiKey: String,
        model: String,
        goal: String,
        toolsOnline: Boolean,
        enabled: (String) -> Boolean
    ): AgentPlan {
        val reply = callAi(baseUrl, apiKey, model, PLANNER_SYSTEM, listOf("user" to goal))
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw PlanException("The AI did not give me a usable plan, so I did nothing.")
        }
        val obj = try {
            JSONObject(reply.substring(start, end + 1))
        } catch (e: Exception) {
            throw PlanException("The AI's plan was not valid, so I did nothing.")
        }
        val arr = obj.optJSONArray("steps")
        val reason = if (obj.isNull("reason")) "" else obj.optString("reason", "").trim()
        if (arr == null || arr.length() == 0) {
            throw PlanException("I could not make a safe plan for that, so I did nothing." + (if (reason.isNotEmpty()) " $reason" else ""))
        }
        val goalLow = goal.lowercase(Locale.ROOT)
        val steps = mutableListOf<AgentStep>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            val cmd = when (item) {
                is JSONObject -> if (item.isNull("command")) "" else item.optString("command", "")
                is String -> item
                else -> ""
            }.trim()
            val skill = AgentRules.classify(cmd)
                ?: throw PlanException("The AI suggested a step I don't allow or understand: \"$cmd\". I did nothing.")
            val words = asked[skill]
            if (words != null && words.none { goalLow.contains(it) }) {
                throw PlanException("The AI added a step you did not ask for (\"$cmd\"), so I did nothing.")
            }
            if (skill == "reminders" && !timeWord.containsMatchIn(cmd)) {
                throw PlanException("A reminder step has no clear time (\"$cmd\"), so I did nothing. Say the time with AM or PM.")
            }
            steps.add(AgentStep(skill, cmd))
        }
        val ordered = AgentRules.reorder(steps)
        val err = AgentRules.validate(ordered, toolsOnline, enabled)
        if (err != null) throw PlanException(err)
        return AgentPlan(goal, ordered, "the online AI")
    }
}

// END OF FILE
