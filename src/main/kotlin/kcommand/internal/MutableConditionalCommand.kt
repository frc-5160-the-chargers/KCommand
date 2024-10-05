package kcommand.internal

import edu.wpi.first.wpilibj2.command.Command
import edu.wpi.first.wpilibj2.command.InstantCommand
import edu.wpi.first.wpilibj2.command.Subsystem

/**
 * An internal command class used by the [kcommand.commandbuilder.CommandBuilder]
 * for the [kcommand.commandbuilder.CommandBuilder.runSequenceIf] API.
 *
 * It stores a map of condition suppliers and commands, running the first command whose
 * respective supplier returns true. Otherwise, it will run the onFalse command.
 * This command only acts as the backbone for if-else chaining within the CommandBuilder class;
 * and should not be instantiated directly.
 */
public class MutableConditionalCommand @PublishedApi internal constructor(
    private val conditionCommandMap: MutableMap<() -> Boolean, Command> = mutableMapOf(),
    private var onFalse: Command = InstantCommand(),
): Command() {
    private lateinit var selected: Command

    init {
        val requirements = mutableSetOf<Subsystem>()
        for (command in conditionCommandMap.values){
            requirements.addAll(command.requirements)
        }
        requirements.addAll(onFalse.requirements)
        addRequirements(*requirements.toTypedArray())
    }

    @PublishedApi
    internal fun addCommand(condition: () -> Boolean, command: Command) {
        conditionCommandMap[condition] = command
    }

    @PublishedApi
    internal fun setOnFalseCommand(onFalse: Command){
        this.onFalse = onFalse
    }

    override fun initialize() {
        for ((condition, command) in conditionCommandMap){
            if (condition()) {
                selected = command
                command.initialize()
                return
            }
        }
        selected = onFalse
        selected.initialize()
    }

    override fun execute(): Unit = selected.execute()

    override fun isFinished(): Boolean = selected.isFinished

    override fun runsWhenDisabled(): Boolean =
        conditionCommandMap.values.all{ it.runsWhenDisabled() } && onFalse.runsWhenDisabled()
}