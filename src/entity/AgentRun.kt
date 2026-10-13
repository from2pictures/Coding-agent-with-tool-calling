package entity

import domain.ExecutionMode
import domain.RunPhase
import java.util.UUID

data class AgentRun(
    val id: UUID,
    val userTask: String,
    val workspaceRoot: String,
    val mode: ExecutionMode,
    val phase: RunPhase
)
