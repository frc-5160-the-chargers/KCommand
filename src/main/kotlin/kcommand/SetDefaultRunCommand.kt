package kcommand

import edu.wpi.first.wpilibj2.command.Command
import edu.wpi.first.wpilibj2.command.RunCommand
import edu.wpi.first.wpilibj2.command.Subsystem

/**
 * A utility function for setting the default [Command] of a [Subsystem] to a [RunCommand]
 * in a shorter and easier way.
 *
 * @see Subsystem.setDefaultCommand
 */
public fun Subsystem.setDefaultRunCommand(
    vararg requirements: Subsystem,
    endBehavior: (Boolean) -> Unit = {},
    toRun: () -> Unit
){
    this.defaultCommand =
        RunCommand(toRun, this, *requirements)
            .finallyDo{ interrupted -> endBehavior(interrupted) }
            .withName("Default Command of " + this::class.simpleName)
}


