package kcommand.internal

import edu.wpi.first.wpilibj2.command.Command
import edu.wpi.first.wpilibj2.command.InstantCommand
import edu.wpi.first.wpilibj2.command.Subsystem

/**
 * A custom version of [edu.wpi.first.wpilibj2.command.ConditionalCommand]
 * used within the [kcommand.commandbuilder.CommandBuilder].
 */
public class ChargerConditionalCommand(
    private val conditionCommandMap: MutableMap<() -> Boolean, Command> = mutableMapOf(),
    private var onFalse: Command = InstantCommand()
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

    public fun addCommand(condition: () -> Boolean, command: Command) {
        conditionCommandMap[condition] = command
    }

    public fun setOnFalseCommand(onFalse: Command){
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
        onFalse.initialize()
    }

    override fun execute(): Unit = selected.execute()

    override fun isFinished(): Boolean = selected.isFinished

    override fun runsWhenDisabled(): Boolean =
        conditionCommandMap.values.all{ it.runsWhenDisabled() } && onFalse.runsWhenDisabled()
}