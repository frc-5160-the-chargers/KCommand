package kcommand.commandbuilder

import edu.wpi.first.wpilibj2.command.*
import kcommand.withLogging

/**
 * The entry point for the CommandBuilder DSL (Domain Specific Language).
 *
 *
 * See [here](https://kotlinlang.org/docs/type-safe-builders.html#how-it-works)
 * for an explanation of DSLs and how they are built.
 *
 * ```
 * val armCommand: Command = buildCommand{
 *      require(arm) // adds requirements across the entire command group
 *
 *      // equivalent to an InstantCommand within a SequentialCommandGroup
 *      runOnce{ // requirements of the entire command group can also be added here
 *          armSubsystem.resetEncoders()
 *      }
 *
 *      // variable initialization is allowed, since the block is essentially a lambda function
 *      val pidController = UnitSuperPIDController(...)
 *      // getOnceDuringRun acts as a value that is refreshed once every time the command is scheduled;
 *      // order is synchronous
 *      val armCurrentPosition by getOnceDuringRun{arm.distalAngle}
 *
 *      // loops through the block below until the condition is met
 *      loopUntil(condition = { abs(pidController.target - arm.distalAngle) < 0.1 }){
 *          armSubsystem.setDistalVoltage(pidController.calculateOutput())
 *      }.modify{ it.unless(booleanSupplier).withTimeout(5) } // All command factory methods must be called from a modify() function call
 *
 * }
 * ```
 * @param name The name of the buildCommand(defaults to "Generic BuildCommand").
 * @param log If true, will log the individual commands that are part of the DSL. Defaults to false.
 * @param block The entry point to the DSL. Has the context of [CommandBuilder].
 */
public inline fun buildCommand(
    name: String = "Unnamed BuildCommand",
    log: Boolean = false,
    block: BuildCommandScope.() -> Unit
): Command {
    val builder = BuildCommandScope().apply(block)

    val subCommands = builder.commands.map{
        if (log) it.withLogging("$name/${it.name}") else it
    }.toTypedArray()

    if (builder.requirements.size > 0) {
        // adds the builder's requirements to the first command
        subCommands[0] = object: WrapperCommand(subCommands[0]) {
            init {
                addRequirements(*builder.requirements.toTypedArray())
            }
        }
    }

    builder.commandModificationBlocked = true
    builder.addingRequirementsLocked = true

    return SequentialCommandGroup(*subCommands)
        .until{ builder.stopped }
        .finallyDo(builder.endBehavior)
        .withLogging("$name/overallCommand")
        .withName(name)
}

/**
* Creates a [buildCommand] that automatically requires a subsystem.
*/
public inline fun Subsystem.buildCommand(
    name: String = "Generic BuildCommand of " + getName(),
    log: Boolean = false,
    block: BuildCommandScope.() -> Unit
): Command {
    val subsystem = this
    return kcommand.commandbuilder.buildCommand(name, log){
        require(subsystem)
        block()
    }
}

/**
 * A scope exclusive to [buildCommand]; this contains things like end behavior and command requirements
 * which aren't used in other places where a [CommandBuilder] scope is asked for
 * (like runSequentially, runParallelUntilAllFinish, etc.)
 */
@CommandBuilderMarker
public class BuildCommandScope: CommandBuilder() {
    public val requirements: LinkedHashSet<Subsystem> = linkedSetOf()

    public var addingRequirementsLocked: Boolean = false

    public var stopped: Boolean = false
        private set

    public var endBehavior: (Boolean) -> Unit = {}
        private set

    /**
     * Adds subsystems that are required across the entire [buildCommand].
     */
    public fun require(vararg requirements: Subsystem){
        if (addingRequirementsLocked){
            error("""
                It looks like you are attempting to add requirements to the buildCommand while it is running.
                This is not allowed.
                
                buildCommand{
                    // correct way to add requirements; outside of any block
                    require(...)
                    
                    runOnce{
                        // does not compile
                        require(...)
                        // compiles, but will NOT WORK!
                        this@buildCommand.addRequirements(...)
                    }
                }
            """.trimIndent())
        }else{
            this.requirements.addAll(requirements)
        }
    }

    /**
     * Runs the function block when the [buildCommand] is finished.
     */
    public fun onEnd(run: (Boolean) -> Unit){
        endBehavior = run
    }

    /**
     * Stops the entire [buildCommand] if the condition is met;
     * otherwise, continues execution.
     * Code within an [onEnd] block will still be run.
     */
    public fun stopIf(condition: () -> Boolean): Command =
        +InstantCommand({
            if (condition()){
                this@BuildCommandScope.stopped = true
            }
        })
}