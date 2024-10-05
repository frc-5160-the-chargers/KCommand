package kcommand.internal

import edu.wpi.first.wpilibj2.command.Command
import edu.wpi.first.wpilibj2.command.WrapperCommand

/**
 * A newtype for a command that allows the parallel block in the
 * command builder to distinguish the deadline command.
 *
 * ```
 * parallel { // has a deadline; the parallel block will end when the deadline ends
 *      +AutoBuilder.followPath().asDeadline()
 *      ...
 * }
 *
 * parallel { // does not have a deadline; parallel block will end when all commands end
 *     +oneCommand
 *     +anotherCommand
 * }
 */
@PublishedApi
internal class DeadlineOfParallelGroup(inner: Command): WrapperCommand(inner)