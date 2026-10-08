package com.chintu.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.CountDownLatch

// status: "done" (checked), "sent" (handed to Android, can't be confirmed) or "failed".
class StepOut(val status: String, val text: String)

class AgentResult(val text: String, val spoken: String)

// Runs a plan one step at a time through the app's own skills and checks each result.
class AgentRunner(
    private val ctx: Context,
    private val reminders: ReminderSkill,
    private val memory: MemoryStore,
    private val tools: Tools,
    private val launcher: AppLauncher,
    private val online: OnlineTools,
    private val skills: SkillSettings
) {
    private val failMarkers = listOf(
        "i couldn't", "i did not", "couldn't find", "internet is required", "returned an error",
        "something went wrong", "is turned off", "isn't ready", "not allowed", "already passed",
        "did you mean", "which city", "what time?", "how long should", "what should i",
        "need permission", "i can only", "no clock app", "failed", "i don't have", "too long"
    )

    private fun isFailure(t: String): Boolean {
        val low = t.lowercase(Locale.ROOT)
        return failMarkers.any { low.contains(it) }
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        val e = error
        if (e != null) throw e
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun runLocal(step: AgentStep): StepOut {
        val cmd = step.command
        val notesBefore = DataControls.noteCount(ctx)
        val remindersBefore = Reminders.load(ctx).size
        val memoriesBefore = memory.all().size
        val reply: String? = when (step.skill) {
            "reminders" -> reminders.handle(cmd, Reminders.notifOk(ctx))?.reply
            "memory" -> memory.handle(cmd)
            "apps" -> launcher.handle(cmd)
            else -> tools.handle(cmd) { id -> skills.enabled(id) }
        }
        if (reply == null) return StepOut("failed", "I didn't understand this step.")
        if (isFailure(reply)) return StepOut("failed", reply)
        // Check that the change really happened, not only that the tool said so.
        when (step.skill) {
            "notes" -> if (DataControls.noteCount(ctx) != notesBefore + 1) {
                return StepOut("failed", "I could not confirm the note was saved. $reply")
            }
            "reminders" -> if (Reminders.load(ctx).size != remindersBefore + 1) {
                return StepOut("failed", "I could not confirm the reminder was created. $reply")
            }
            "memory" -> if (memory.all().size != memoriesBefore + 1) {
                return StepOut("failed", "I could not confirm the memory was saved. $reply")
            }
        }
        val sent = step.skill == "timer" || step.skill == "apps"
        return StepOut(if (sent) "sent" else "done", reply)
    }

    private fun runStep(step: AgentStep, toolsOnline: Boolean): StepOut {
        if (!skills.enabled(step.skill)) {
            return StepOut("failed", "The ${SkillCatalog.nameOf(step.skill)} skill is turned off.")
        }
        if (step.skill == "weather" || step.skill == "wiki" || step.skill == "search") {
            if (!toolsOnline) return StepOut("failed", "That needs the internet, but Private or OFFLINE mode is on.")
            val req = online.parse(step.command) ?: return StepOut("failed", "I didn't understand this step.")
            if (req.kind == "weather" && req.arg.isBlank()) {
                return StepOut("failed", "Which city? I have no default city saved.")
            }
            PrivacyLog.record(online.hostFor(req))
            val out = online.run(req)
            return if (out.ok) StepOut("done", out.text) else StepOut("failed", out.text)
        }
        return onMain { runLocal(step) }
    }

    // Blocking. Call it from a background thread. onProgress is called before each step starts.
    fun run(plan: AgentPlan, toolsOnline: Boolean, onProgress: (Int, Int, AgentStep) -> Unit): AgentResult {
        val n = plan.steps.size
        val outs = mutableListOf<StepOut>()
        var failedAt = -1
        for (i in 0 until n) {
            val s = plan.steps[i]
            onProgress(i + 1, n, s)
            val o = try {
                runStep(s, toolsOnline)
            } catch (e: Exception) {
                StepOut("failed", "Something went wrong: ${e.javaClass.simpleName}: ${e.message}")
            }
            outs.add(o)
            if (o.status == "failed") {
                failedAt = i
                break
            }
        }
        val worked = outs.count { it.status != "failed" }
        val sb = StringBuilder()
        if (failedAt < 0) sb.append("Plan finished: all $n steps worked.\n")
        else sb.append("Plan stopped at step ${failedAt + 1} of $n: $worked of $n steps worked.\n")
        for (i in 0 until n) {
            val o = outs.getOrNull(i)
            val label = when {
                o == null -> "NOT RUN"
                o.status == "done" -> "DONE"
                o.status == "sent" -> "SENT (can't be confirmed)"
                else -> "FAILED"
            }
            sb.append("${i + 1}. $label: ${plan.steps[i].command}")
            val detail = o?.text?.substringBefore("\n\n")?.replace("\n", " ")?.take(160) ?: ""
            if (detail.isNotEmpty()) sb.append(" - ").append(detail)
            sb.append("\n")
        }
        if (failedAt >= 0) {
            sb.append("I stopped here so nothing else changes by mistake. You can send the remaining steps one by one as separate messages.")
        }
        val spoken = if (failedAt < 0) "Done. All $n steps worked."
        else "I stopped at step ${failedAt + 1}. $worked of $n steps worked."
        return AgentResult(sb.toString().trim(), spoken)
    }
}

// END OF FILE
