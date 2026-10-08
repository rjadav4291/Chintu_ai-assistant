package com.chintu.assistant

// Asks the online AI to turn a goal into a safe plan. Not built yet: part 14B adds it.
object AgentPlanner {
    fun planWithAi(
        baseUrl: String,
        apiKey: String,
        model: String,
        goal: String,
        toolsOnline: Boolean,
        enabled: (String) -> Boolean
    ): AgentPlan {
        throw PlanException(
            "Planning with the AI is not built yet. It comes in the next part. For now, join clear commands with \"then\", for example: weather in Surat then remind me at 7 PM to take an umbrella."
        )
    }
}

// END OF FILE
