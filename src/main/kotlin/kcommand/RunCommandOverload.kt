package kcommand

import edu.wpi.first.wpilibj2.command.RunCommand
import edu.wpi.first.wpilibj2.command.Subsystem


/**
 * A utility function for creating [RunCommand]s in a more kotlin-friendly way.
 *
 * Because of the lambda function being placed at the end, it can now use kotlin's
 * external lambda block syntax.
 *
 * ```
 * // this
 * val runCommand = RunCommand(drivetrain, arm){
 *      arm.move()
 *      drivetrain.drive()
 * }
 *
 * // instead of this
 * val runCommand = RunCommand({arm.move(); drivetrain.drive();}, drivetrain, arm)
 */
public fun RunCommand(vararg subsystems: Subsystem, toRun: () -> Unit): RunCommand =
    RunCommand(toRun, *subsystems)